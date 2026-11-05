package com.kangheng.healthos.service.qa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.kangheng.healthos.model.qa.Citation;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.model.tracing.Span;
import com.kangheng.healthos.service.tracing.TraceContext;
import com.kangheng.healthos.service.tracing.TracingService;
import com.kangheng.healthos.spi.qa.AnswerBranch;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.spi.qa.BranchResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 多路并行问答编排：<b>各跑各的、互不阻塞、按完成情况汇总</b>。
 *
 * <p>语义对齐原工程 {@code qa_worker}：
 * <ul>
 *   <li>所有分支成功 → 汇总成一条主答案；</li>
 *   <li>有分支失败/超时 → 已完成部分照常返回，错误码 {@code QA_BRANCHES_INCOMPLETE}
 *       （与原工程同名），<b>不把整次问答判死</b>；</li>
 *   <li>全部无依据 → {@code INSUFFICIENT_EVIDENCE}，不生成自由发挥的答案。</li>
 * </ul>
 *
 * <p>与原工程的差别（写在明面上）：原工程给每个节点配了租约、心跳、尝试次数与退避重试，
 * 失败后由 worker 断点续跑。这里是单次请求内的内存编排，超时即判失败，不做持久化重试。
 * 好处是延迟可预测，代价是长任务无法跨进程续跑——见 README 已知限制。
 */
@Component
public class BranchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BranchOrchestrator.class);

    public static final String STATUS_OK = "OK";
    public static final String STATUS_PARTIAL = "BRANCHES_INCOMPLETE";
    public static final String STATUS_INSUFFICIENT = "INSUFFICIENT_EVIDENCE";

    public static final String ERROR_INCOMPLETE = "QA_BRANCHES_INCOMPLETE";
    public static final String ERROR_STEP_FAILED = "QA_STEP_FAILED";
    public static final String ERROR_TIMEOUT = "QA_BRANCH_TIMEOUT";

    private final List<AnswerBranch> branches;
    private final ExecutorService executor;
    private final long timeoutMs;
    private final TracingService tracing;

    public BranchOrchestrator(List<AnswerBranch> branches,
                              @Qualifier("qaBranchExecutor") ExecutorService executor,
                              HealthOsProperties properties,
                              TracingService tracing) {
        this.branches = branches.stream()
                .sorted(Comparator.comparingInt(AnswerBranch::order)
                        .thenComparing(AnswerBranch::key))
                .toList();
        this.executor = executor;
        this.tracing = tracing;
        // 下限 100ms：配置成 0 会让所有分支立刻超时，宁可兜一个能跑通的值
        this.timeoutMs = Math.max(100, properties.getQa().getBranchTimeout().toMillis());
    }

    /** 已注册的分支（按展示顺序），供接口自描述与健康检查使用。 */
    public List<Map<String, Object>> descriptors() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AnswerBranch branch : branches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", branch.key());
            item.put("label", branch.label());
            item.put("order", branch.order());
            list.add(item);
        }
        return list;
    }

    /** 已注册的分支实例（按执行顺序）。持久化 run 用它把分支映射成节点。 */
    public List<AnswerBranch> branchList() {
        return branches;
    }

    /** 按 key 取分支；找不到返回 null。 */
    public AnswerBranch branch(String key) {
        for (AnswerBranch branch : branches) {
            if (branch.key().equals(key)) {
                return branch;
            }
        }
        return null;
    }

    public Outcome run(BranchContext context) {
        if (branches.isEmpty()) {
            return new Outcome(STATUS_INSUFFICIENT, "QA_NO_BRANCH", null, List.of(), List.of());
        }
        return combine(runBranches(context, branches));
    }

    /**
     * 只跑指定的分支（按给定顺序），返回每路结果，<b>不汇总</b>。
     *
     * <p>持久化 run 的续跑要用它：已成功的节点直接复用上次的结果，
     * 只有失败/未跑的分支才真的执行一遍。
     */
    public List<BranchResult> runBranches(BranchContext context, List<AnswerBranch> selected) {
        if (selected.isEmpty()) {
            return List.of();
        }
        Map<String, Future<BranchResult>> futures = new LinkedHashMap<>();
        // 分支跑在独立线程池里，ThreadLocal 不会自动过去：这里显式把轨迹快照搬进任务，
        // 否则三路分支的 span 会全部变成"无父节点"的孤岛。
        TraceContext.Frame frame = TraceContext.current();
        for (AnswerBranch branch : selected) {
            futures.put(branch.key(), executor.submit(() -> tracing.callIn(frame, () -> {
                try (Span span = tracing.start("branch." + branch.key(), "business_stage", "qa-branch")) {
                    span.attribute("key", branch.key());
                    try {
                        BranchResult result = branch.answer(context);
                        span.attribute("status", result.status())
                                .attribute("basis", result.basis())
                                .attribute("citations",
                                        result.citations() == null ? 0 : result.citations().size())
                                .attribute("elapsedMs", result.elapsedMs());
                        return result;
                    } catch (RuntimeException ex) {
                        // 分支内部抛错不该拖垮其它路：转成该路 FAILED，由汇总如实呈现
                        span.failed(ERROR_STEP_FAILED, ex.getMessage());
                        log.warn("问答分支 {} 执行失败: {}", branch.key(), ex.toString());
                        return BranchResult.failed(branch.key(), branch.label(), ERROR_STEP_FAILED,
                                ex.getMessage(), 0);
                    }
                }
            })));
        }

        List<BranchResult> results = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (Map.Entry<String, Future<BranchResult>> entry : futures.entrySet()) {
            long remaining = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            try {
                results.add(entry.getValue().get(remaining, TimeUnit.MILLISECONDS));
            } catch (TimeoutException ex) {
                entry.getValue().cancel(true);
                results.add(BranchResult.failed(entry.getKey(), labelOf(entry.getKey()),
                        ERROR_TIMEOUT, "该分支超过 " + timeoutMs + "ms 未完成，已放弃", timeoutMs));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                results.add(BranchResult.failed(entry.getKey(), labelOf(entry.getKey()),
                        ERROR_STEP_FAILED, "编排被中断", 0));
            } catch (ExecutionException ex) {
                results.add(BranchResult.failed(entry.getKey(), labelOf(entry.getKey()),
                        ERROR_STEP_FAILED, String.valueOf(ex.getCause()), 0));
            }
        }
        return results;
    }

    private String labelOf(String key) {
        for (AnswerBranch branch : branches) {
            if (branch.key().equals(key)) {
                return branch.label();
            }
        }
        return key;
    }

    /**
     * @param status     OK / BRANCHES_INCOMPLETE / INSUFFICIENT_EVIDENCE
     * @param errorCode  仅在不完整或全无依据时给出
     * @param branches   每路明细，按展示顺序
     */
    public record Outcome(String status, String errorCode, String answer,
                          List<Citation> citations, List<BranchResult> branches) {
    }

    /** 汇总规则只在 {@link BranchAggregator} 里写一份，这里转个形状而已。 */
    private Outcome combine(List<BranchResult> results) {
        BranchAggregator.Aggregate aggregate = BranchAggregator.combine(results);
        return new Outcome(aggregate.status(), aggregate.errorCode(), aggregate.answer(),
                aggregate.citations(), aggregate.branches());
    }
}
