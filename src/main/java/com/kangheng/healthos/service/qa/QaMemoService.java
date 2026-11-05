package com.kangheng.healthos.service.qa;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.qa.QaAuxiliaryContract;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.qa.QaConversationMapper;
import com.kangheng.healthos.mapper.qa.QaMemoMapper;
import com.kangheng.healthos.mapper.qa.QaMessageMapper;
import com.kangheng.healthos.mapper.qa.QaRunMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.qa.QaConversation;
import com.kangheng.healthos.model.qa.QaMemo;
import com.kangheng.healthos.model.qa.QaMessage;
import com.kangheng.healthos.model.qa.QaRun;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * QA 书签 / 备忘（对齐原工程 {@code qa_memos.py}）。
 *
 * <p>三条不可让步的语义：
 * <ol>
 *   <li><b>只有显式点击才创建</b>。同步问答、异步 run 完成都不自动写这张表——
 *       自动收藏会把"用户认定有价值的结论"稀释成"系统答过的每一句"；</li>
 *   <li><b>存的是冻结快照</b>。引用与来源定位在保存那一刻从消息里复制出来，
 *       之后源文档重新解析也不影响这份备忘（详见 {@code QaMemo}）；</li>
 *   <li><b>按 owner 隔离，查不到返回 404</b>。消息归属通过 conversation 反查，
 *       403 会承认"这个 ID 确实存在"，本身就是信息泄露。</li>
 * </ol>
 */
@Service
public class QaMemoService {

    public static final String ERROR_MESSAGE_NOT_FOUND = "MESSAGE_NOT_FOUND";
    public static final String ERROR_ANSWER_NOT_COMPLETE = "MEMO_ANSWER_NOT_COMPLETE";
    public static final String ERROR_SOURCE_MISSING = "MEMO_SOURCE_MISSING";
    public static final String ERROR_MEMO_NOT_FOUND = "MEMO_NOT_FOUND";
    public static final String ERROR_CURSOR_INVALID = "MEMO_CURSOR_INVALID";

    /** 消息"完成态"：这几种状态下答案已经定稿，不存在写一半的助手消息。 */
    private static final Set<String> COMPLETED_MESSAGE_STATUS =
            Set.of("OK", "SUCCEEDED", "INSUFFICIENT_EVIDENCE");

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final QaMemoMapper memoMapper;
    private final QaMessageMapper messageMapper;
    private final QaConversationMapper conversationMapper;
    private final QaRunMapper runMapper;
    private final PatientMapper patientMapper;

    public QaMemoService(QaMemoMapper memoMapper,
                         QaMessageMapper messageMapper,
                         QaConversationMapper conversationMapper,
                         QaRunMapper runMapper,
                         PatientMapper patientMapper) {
        this.memoMapper = memoMapper;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.runMapper = runMapper;
        this.patientMapper = patientMapper;
    }

    // ================================================================ 创建

    /**
     * 显式收藏一条助手答案为备忘。
     *
     * <p>幂等：{@code sourceMessageId} 唯一，重复点击直接返回既有备忘，不新建、不报错。
     * 这比"先报错再由前端处理"省事，也更符合按钮连点两次的真实场景。
     */
    public QaAuxiliaryContract.MemoCreated create(String username, String messageId) {
        String owner = normalize(username);

        QaMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BizException(ERROR_MESSAGE_NOT_FOUND, "消息不存在", HttpStatus.NOT_FOUND);
        }

        // 消息本身不带 owner，归属要通过 conversation 反查。
        // conversation 不存在或不属于当前用户都返回 404：查不到一律当作"不存在"。
        QaConversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null || !AccessControl.sameUser(owner, conversation.getCreatedBy())) {
            throw new BizException(ERROR_MESSAGE_NOT_FOUND, "消息不存在", HttpStatus.NOT_FOUND);
        }

        QaMemo existing = memoMapper.selectOne(new LambdaQueryWrapper<QaMemo>()
                .eq(QaMemo::getSourceMessageId, messageId)
                .last("LIMIT 1"));
        if (existing != null) {
            // 同一条消息只可能属于同一个 owner；这里再挡一次是防御脏数据串号。
            if (!AccessControl.sameUser(owner, existing.getOwner())) {
                throw new BizException(ERROR_MESSAGE_NOT_FOUND, "消息不存在", HttpStatus.NOT_FOUND);
            }
            return created(existing);
        }

        // ---- 完成态校验 ----
        if (!"assistant".equals(message.getRole())
                || !StringUtils.hasText(message.getContent())
                || !COMPLETED_MESSAGE_STATUS.contains(message.getStatus())) {
            throw new BizException(ERROR_ANSWER_NOT_COMPLETE,
                    "只能收藏已完成的助手答案", HttpStatus.BAD_REQUEST);
        }

        QaRun run = latestRun(conversation.getId());
        if (run != null && !"SUCCEEDED".equals(run.getStatus())) {
            throw new BizException(ERROR_ANSWER_NOT_COMPLETE,
                    "对应问答运行尚未成功完成", HttpStatus.BAD_REQUEST);
        }
        // 放宽说明（对齐任务里"若无关联 run 可放宽为仅检查 message 状态"）：
        // QaMessage 没有 runId，同步 /qa/ask 也完全不落 QaRun，无法把消息与某次运行一一关联；
        // 因此 run == null 时不再强求"运行已成功"，仅凭上面的 message 完成态判定。
        // 但 question / patientId 的唯一来源仍是这次 run：取不到就以 MEMO_SOURCE_MISSING 拒绝，
        // 而不是静默存一条没有患者归属、也没有问题的孤儿备忘（列表是按患者维度查的）。
        // patientId 也必须存在：备忘是按患者维度列表查询的，缺它就没有归属维度。
        // 早期同步问答运行可能没有 patientId，这里明确拒绝（400），
        // 而不是让 patient_id 的非空约束把请求变成 500。
        if (run == null || !StringUtils.hasText(run.getPatientId())) {
            throw new BizException(ERROR_SOURCE_MISSING,
                    "找不到该会话的问答运行来源或患者归属，无法创建备忘", HttpStatus.BAD_REQUEST);
        }

        String content = message.getContent();
        List<Map<String, Object>> evidence = parseEvidence(message.getCitations());
        List<Map<String, Object>> sourceVersions = sourceVersions(evidence);

        QaMemo memo = new QaMemo();
        memo.setId(Ids.newId("qmem"));
        memo.setOwner(owner);
        memo.setPatientId(run.getPatientId());
        memo.setSourceMessageId(messageId);
        memo.setQuestion(run.getQuestion());
        memo.setContent(content);
        memo.setEvidenceSnapshot(Json.write(evidence));
        memo.setSourceVersions(Json.write(sourceVersions));
        memo.setContentHash(Ids.sha256Hex(content));
        memo.setCreatedAt(OffsetDateTime.now());
        memoMapper.insert(memo);
        return created(memo);
    }

    // ================================================================ 查询

    /**
     * 患者维度列表，键集分页（created_at / id 倒序）。
     *
     * <p><b>为什么不用 offset</b>：用户翻页期间又收藏了一条新备忘，offset 会让下一页
     * 要么漏一条、要么重复一条。游标把"上次看到哪"编码进请求，插入不影响后续页面。
     */
    public QaAuxiliaryContract.MemoPage list(String username, String patientId,
                                             String before, Integer limit) {
        String owner = normalize(username);
        requirePatient(owner, patientId);
        int capped = capLimit(limit);
        Cursor cursor = decodeCursor(before);

        LambdaQueryWrapper<QaMemo> wrapper = new LambdaQueryWrapper<QaMemo>()
                .eq(QaMemo::getOwner, owner)
                .eq(QaMemo::getPatientId, patientId);
        if (cursor != null) {
            // (created_at, id) < (cursor.time, cursor.id)，与 orderByDesc 同序。
            // 同一毫秒内可能有多条，必须用 id 做第二排序键才能不重不漏。
            OffsetDateTime time = cursor.time();
            String id = cursor.id();
            wrapper.and(w -> w.lt(QaMemo::getCreatedAt, time)
                    .or(x -> x.eq(QaMemo::getCreatedAt, time).lt(QaMemo::getId, id)));
        }
        wrapper.orderByDesc(QaMemo::getCreatedAt)
                .orderByDesc(QaMemo::getId)
                .last("LIMIT " + (capped + 1));

        List<QaMemo> rows = memoMapper.selectList(wrapper);
        boolean hasMore = rows.size() > capped;
        List<QaMemo> page = hasMore ? rows.subList(0, capped) : rows;
        List<QaAuxiliaryContract.MemoItem> items = new ArrayList<>();
        for (QaMemo memo : page) {
            items.add(new QaAuxiliaryContract.MemoItem(memo.getId(), memo.getSourceMessageId(),
                    memo.getQuestion(), memo.getContentHash(), memo.getCreatedAt()));
        }
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                : null;
        return new QaAuxiliaryContract.MemoPage(items, nextCursor);
    }

    /** 完整视图。越权/不存在一律 404。 */
    public QaAuxiliaryContract.MemoDetail detail(String username, String memoId) {
        QaMemo memo = requireOwned(memoId, username);
        return new QaAuxiliaryContract.MemoDetail(memo.getId(), memo.getPatientId(),
                memo.getSourceMessageId(), memo.getCreatedAt(), memo.getQuestion(), memo.getContent(),
                parseEvidence(memo.getEvidenceSnapshot()), parseEvidence(memo.getSourceVersions()),
                memo.getContentHash());
    }

    // ================================================================ 内部

    private QaRun latestRun(String conversationId) {
        return runMapper.selectOne(new LambdaQueryWrapper<QaRun>()
                .eq(QaRun::getConversationId, conversationId)
                .orderByDesc(QaRun::getCreatedAt)
                .last("LIMIT 1"));
    }

    /** 患者归属校验：越权返回 404，不返回 403。 */
    private void requirePatient(String owner, String patientId) {
        Patient patient = patientMapper.selectById(patientId);
        if (patient == null || !AccessControl.sameUser(owner, patient.getCreatedBy())) {
            throw BizException.notFound("患者档案");
        }
    }

    private QaMemo requireOwned(String memoId, String username) {
        QaMemo memo = memoMapper.selectById(memoId);
        if (memo == null || !AccessControl.sameUser(normalize(username), memo.getOwner())) {
            throw new BizException(ERROR_MEMO_NOT_FOUND, "备忘不存在", HttpStatus.NOT_FOUND);
        }
        return memo;
    }

    private QaAuxiliaryContract.MemoCreated created(QaMemo memo) {
        return new QaAuxiliaryContract.MemoCreated(memo.getId(), memo.getPatientId(),
                memo.getSourceMessageId(), memo.getCreatedAt());
    }

    /**
     * 把 citations / 已存的快照列解析成 JSON 数组。
     *
     * <p>空值、坏 JSON 都退化成空数组而不是抛异常：历史消息里可能存在手工写入的
     * 非 JSON 引用文本，收藏动作不该因为一段脏引用就整体失败。
     */
    private List<Map<String, Object>> parseEvidence(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<?, ?> item : Json.readList(json, Map.class)) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : item.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            result.add(copy);
        }
        return result;
    }

    /**
     * 从引用快照里抽出"来源定位"冻结下来。
     *
     * <p>只记 {@code documentId + evidenceId}，不记正文、不记页码：备忘要能回答
     * "这条依据当年出自哪份文档的哪个切片"，版本号/页码会随重新解析变化，记了反而是噪声。
     */
    private List<Map<String, Object>> sourceVersions(List<Map<String, Object>> evidence) {
        List<Map<String, Object>> versions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : evidence) {
            String documentId = item.get("documentId") == null ? null : String.valueOf(item.get("documentId"));
            String evidenceId = item.get("evidenceId") == null ? null : String.valueOf(item.get("evidenceId"));
            if (!seen.add(documentId + "|" + evidenceId)) {
                continue;
            }
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("documentId", documentId);
            version.put("evidenceId", evidenceId);
            versions.add(version);
        }
        return versions;
    }

    private int capLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private String normalize(String username) {
        return username == null ? "unknown" : username;
    }

    // ---------------------------------------------------------------- 游标

    private record Cursor(OffsetDateTime time, String id) {
    }

    private String encodeCursor(OffsetDateTime time, String id) {
        String raw = time + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String before) {
        if (!StringUtils.hasText(before)) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(before), StandardCharsets.UTF_8);
            int split = raw.lastIndexOf('|');
            if (split <= 0 || split == raw.length() - 1) {
                throw new IllegalArgumentException("游标缺少分隔");
            }
            return new Cursor(OffsetDateTime.parse(raw.substring(0, split)), raw.substring(split + 1));
        } catch (RuntimeException ex) {
            throw new BizException(ERROR_CURSOR_INVALID, "分页游标无效",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
