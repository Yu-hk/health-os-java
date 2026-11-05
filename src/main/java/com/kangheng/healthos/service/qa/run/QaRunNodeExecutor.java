package com.kangheng.healthos.service.qa.run;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import com.kangheng.healthos.model.qa.Citation;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.qa.QaContract;
import com.kangheng.healthos.mapper.qa.QaAttemptMapper;
import com.kangheng.healthos.mapper.qa.QaEventMapper;
import com.kangheng.healthos.mapper.qa.QaRunNodeMapper;
import com.kangheng.healthos.model.qa.QaAttempt;
import com.kangheng.healthos.model.qa.QaEvent;
import com.kangheng.healthos.model.qa.QaRun;
import com.kangheng.healthos.model.qa.QaRunNode;
import com.kangheng.healthos.spi.qa.AnswerBranch;
import com.kangheng.healthos.service.qa.BranchAggregator;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.service.qa.BranchOrchestrator;
import com.kangheng.healthos.spi.qa.BranchResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 节点执行器：真正干活的那些节点在这里跑。
 *
 * <p>与 {@link BranchOrchestrator} 的分工：
 * <ul>
 *   <li>编排器管"一次请求内的并行与超时"，结果是内存里的对象；</li>
 *   <li>这里管"落库的节点生命周期"——每次尝试留痕、状态写回、失败可重试。</li>
 * </ul>
 * 两者共用同一批 {@link AnswerBranch} 实现，所以同一份问题，
 * 走同步接口和走持久化 run，得到的答案必须一致。
 */
@Component
public class QaRunNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(QaRunNodeExecutor.class);

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private final BranchOrchestrator orchestrator;
    private final QaRunNodeMapper nodeMapper;
    private final QaEventMapper eventMapper;
    private final QaAttemptMapper attemptMapper;

    public QaRunNodeExecutor(BranchOrchestrator orchestrator,
                             QaRunNodeMapper nodeMapper,
                             QaEventMapper eventMapper,
                             QaAttemptMapper attemptMapper) {
        this.orchestrator = orchestrator;
        this.nodeMapper = nodeMapper;
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
    }

    /** 已注册的分支（按执行顺序）。建节点时用它把分支映射成节点。 */
    public List<AnswerBranch> branches() {
        return orchestrator.branchList();
    }

    /**
     * 执行一个节点，返回更新后的节点。
     *
     * @param context 已冻结的分支上下文
     */
    public QaRunNode execute(QaRun run, QaRunNode node, BranchContext context) {
        int attemptNo = (node.getAttemptCount() == null ? 0 : node.getAttemptCount()) + 1;
        QaAttempt attempt = startAttempt(run, node, attemptNo);

        markNodeRunning(node);
        emit(run.getId(), "node.started",
                Map.of("nodeKey", node.getNodeKey(), "attempt", attemptNo));

        try {
            Map<String, Object> result = QaNodeSpec.SUMMARY.equals(node.getNodeKey())
                    ? runSummary(run)
                    : runBranch(node, context);
            finishAttempt(attempt.getId(), null, null, result);
            finishNode(node, STATUS_SUCCEEDED, result, null, null);
            emit(run.getId(), "node.succeeded",
                    Map.of("nodeKey", node.getNodeKey(), "attempt", attemptNo));
            return node;
        } catch (RuntimeException ex) {
            String code = ex instanceof BizException biz ? biz.getCode() : "QA_NODE_FAILED";
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.warn("问答节点执行失败 runId={} node={} attempt={}: {}",
                    run.getId(), node.getNodeKey(), attemptNo, message);
            finishAttempt(attempt.getId(), code, message, null);
            finishNode(node, STATUS_FAILED, null, code, message);
            emit(run.getId(), "node.failed",
                    Map.of("nodeKey", node.getNodeKey(), "attempt", attemptNo, "errorCode", code));
            return node;
        }
    }

    // ---------------------------------------------------------------- 分支节点

    private Map<String, Object> runBranch(QaRunNode node, BranchContext context) {
        AnswerBranch branch = orchestrator.branch(node.getNodeKey());
        if (branch == null) {
            throw new BizException("QA_BRANCH_NOT_FOUND", "分支不存在: " + node.getNodeKey(),
                    HttpStatus.NOT_FOUND);
        }
        List<BranchResult> results = orchestrator.runBranches(context, List.of(branch));
        BranchResult result = results.isEmpty() ? null : results.get(0);
        if (result == null) {
            throw new IllegalStateException("分支没有返回结果: " + node.getNodeKey());
        }
        // 分支自己报 FAILED 时，节点也算失败——这样才能按节点重试。
        // 但 INSUFFICIENT_EVIDENCE 不是失败：它表示"这一路正常跑完但没依据"，
        // 是正常结论，不该触发重试。
        if (result.failed()) {
            throw new BizException(
                    result.errorCode() == null ? "QA_BRANCH_FAILED" : result.errorCode(),
                    result.note() == null ? "分支执行失败" : result.note(),
                    HttpStatus.CONFLICT);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status());
        payload.put("basis", result.basis());
        payload.put("answer", result.answer());
        payload.put("citations", result.citations());
        payload.put("elapsedMs", result.elapsedMs());
        payload.put("note", result.note());
        return payload;
    }

    // ---------------------------------------------------------------- 汇总节点

    /**
     * 汇总：把各分支节点的结果合成最终答案。
     *
     * <p>关键点：汇总读的是<b>已落库的节点结果</b>，不是内存里的对象。
     * 这样续跑时，续跑之前就成功的分支照样参与汇总——
     * 否则"只重跑失败分支"的结果会丢掉其它分支的依据。
     */
    private Map<String, Object> runSummary(QaRun run) {
        List<BranchResult> results = new ArrayList<>();
        for (QaRunNode node : listNodes(run.getId())) {
            if (!QaNodeSpec.isBranch(node.getNodeKey())) {
                continue;
            }
            Map<String, Object> payload = Json.readMap(node.getResult());
            if (payload.isEmpty()) {
                continue;
            }
            results.add(new BranchResult(
                    node.getNodeKey(), node.getLabel(),
                    str(payload.get("status")), str(payload.get("basis")),
                    str(payload.get("answer")),
                    readCitations(payload.get("citations")),
                    null,
                    payload.get("elapsedMs") instanceof Number number ? number.longValue() : 0L,
                    str(payload.get("note"))));
        }
        BranchAggregator.Aggregate aggregate = BranchAggregator.combine(results);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", aggregate.status());
        payload.put("errorCode", aggregate.errorCode());
        payload.put("answer", aggregate.answer());
        payload.put("citations", aggregate.citations());
        payload.put("branches", aggregate.branches().stream()
                .map(item -> new QaContract.BranchView(item.key(), item.label(), item.status(),
                        item.basis(), item.answer(), item.errorCode(), item.elapsedMs(),
                        item.note(), item.citations()))
                .toList());
        return payload;
    }

    private List<QaRunNode> listNodes(String runId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<QaRunNode>()
                .eq(QaRunNode::getRunId, runId)
                .orderByAsc(QaRunNode::getOrderIndex));
    }

    // ---------------------------------------------------------------- 状态写回

    private void markNodeRunning(QaRunNode node) {
        QaRunNode update = new QaRunNode();
        update.setId(node.getId());
        update.setStatus(STATUS_RUNNING);
        update.setStartedAt(OffsetDateTime.now());
        update.setUpdatedAt(OffsetDateTime.now());
        nodeMapper.updateById(update);
        node.setStatus(STATUS_RUNNING);
    }

    private void finishNode(QaRunNode node, String status, Map<String, Object> result,
                            String errorCode, String errorMessage) {
        // 尝试次数先算好：wrapper 与内存对象都要用同一个值，
        // 各算一次会变成两次递增。
        int attempts = (node.getAttemptCount() == null ? 0 : node.getAttemptCount()) + 1;
        // 显式 set null：updateById 忽略 null 字段，
        // 重试成功后上一次失败的错误码还留在节点上，看起来像仍然失败。
        nodeMapper.update(null, new LambdaUpdateWrapper<QaRunNode>()
                .eq(QaRunNode::getId, node.getId())
                .set(QaRunNode::getStatus, status)
                .set(QaRunNode::getResult, result == null ? null : Json.write(result))
                .set(QaRunNode::getErrorCode, errorCode)
                .set(QaRunNode::getErrorMessage, errorMessage)
                .set(QaRunNode::getCompletedAt, OffsetDateTime.now())
                .set(QaRunNode::getUpdatedAt, OffsetDateTime.now())
                .set(QaRunNode::getAttemptCount, attempts));
        node.setStatus(status);
        node.setAttemptCount(attempts);
    }

    private QaAttempt startAttempt(QaRun run, QaRunNode node, int attemptNo) {
        QaAttempt attempt = new QaAttempt();
        attempt.setId(Ids.newId("qatt"));
        attempt.setRunId(run.getId());
        attempt.setNodeKey(node.getNodeKey());
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus("RUNNING");
        attempt.setInputHash(Ids.sha256Hex(run.getId() + "|" + node.getNodeKey() + "|" + attemptNo));
        attempt.setStartedAt(OffsetDateTime.now());
        attemptMapper.insert(attempt);
        return attempt;
    }

    private void finishAttempt(String attemptId, String errorCode, String errorMessage,
                               Map<String, Object> detail) {
        QaAttempt update = new QaAttempt();
        update.setId(attemptId);
        update.setStatus(errorCode == null ? "SUCCEEDED" : "FAILED");
        update.setErrorCode(errorCode);
        update.setErrorMessage(errorMessage);
        update.setDetail(detail == null ? null : Json.write(detail));
        update.setCompletedAt(OffsetDateTime.now());
        attemptMapper.updateById(update);
    }

    private void emit(String runId, String type, Map<String, Object> payload) {
        Integer seq = eventMapper.nextSeq(runId);
        if (seq == null) {
            log.warn("问答运行不存在，无法写事件 runId={} type={}", runId, type);
            return;
        }
        QaEvent event = new QaEvent();
        event.setId(Ids.newId("qevt"));
        event.setRunId(runId);
        event.setSeq(seq);
        event.setType(type);
        event.setPayload(payload == null ? null : Json.write(payload));
        event.setCreatedAt(OffsetDateTime.now());
        eventMapper.insert(event);
    }

    /**
     * 读回节点结果里的引用列表。
     *
     * <p>节点结果是从 JSON 反序列化出来的，引用元素是 {@code LinkedHashMap} 而不是
     * {@link Citation}——直接强转会 ClassCastException，
     * 所以这里再走一次 JSON 转换还原成真正的类型。
     */
    private List<Citation> readCitations(Object value) {
        if (value == null) {
            return List.of();
        }
        return Json.readList(Json.write(value), Citation.class);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
