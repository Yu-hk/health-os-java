package com.kangheng.healthos.service.qa;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kangheng.healthos.model.qa.Citation;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.provider.tracing.DirectTraceWriter;
import com.kangheng.healthos.service.tracing.TracingService;
import com.kangheng.healthos.spi.qa.AnswerBranch;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.spi.qa.BranchResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BranchOrchestratorTest {

    private ExecutorService executor;
    private HealthOsProperties properties;
    private TracingService tracing;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        properties = new HealthOsProperties();
        properties.getQa().setBranchTimeout(Duration.ofSeconds(2));
        // 用真实的 TracingService（关闭观测）而不是 mock：
        // mock 的 callIn 会直接返回 null，把分支体整个吞掉，测出来的东西就没意义了。
        HealthOsProperties tracingProps = new HealthOsProperties();
        tracingProps.getTracing().setEnabled(false);
        tracing = new TracingService(mock(TraceRunMapper.class), mock(TraceSpanMapper.class),
                mock(TraceEventMapper.class), new DirectTraceWriter(), tracingProps);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("两路成功 + 一路无依据：汇总两路内容，状态仍为 OK")
    void mergesSucceededBranches() {
        BranchOrchestrator orchestrator = new BranchOrchestrator(List.of(
                fake("a", "古文", 10, BranchResult.succeeded("a", "古文",
                        BranchResult.BASIS_RETRIEVED_EVIDENCE, "原文答案",
                        List.of(citation("c1", 1)), 1)),
                fake("b", "图谱", 20, BranchResult.succeeded("b", "图谱",
                        BranchResult.BASIS_RETRIEVED_EVIDENCE, "图谱答案",
                        List.of(citation("c2", 2)), 1)),
                fake("c", "指标", 30, BranchResult.insufficient("c", "指标", "无指标", 0))
        ), executor, properties, tracing);

        BranchOrchestrator.Outcome outcome = orchestrator.run(context());

        assertThat(outcome.status()).isEqualTo(BranchOrchestrator.STATUS_OK);
        assertThat(outcome.errorCode()).isNull();
        assertThat(outcome.answer()).contains("【古文】").contains("【图谱】")
                .contains("未纳入汇总：指标（无依据）");
        assertThat(outcome.citations()).hasSize(2);
        assertThat(outcome.branches()).hasSize(3);
    }

    @Test
    @DisplayName("单路成功时直接取其答案，不额外套分支标题")
    void singleSucceededAnswerIsUsedVerbatim() {
        BranchOrchestrator orchestrator = new BranchOrchestrator(List.of(
                fake("a", "古文", 10, BranchResult.succeeded("a", "古文",
                        BranchResult.BASIS_RETRIEVED_EVIDENCE, "唯一答案",
                        List.of(citation("c1", 1)), 1)),
                fake("b", "图谱", 20, BranchResult.insufficient("b", "图谱", "无", 0))
        ), executor, properties, tracing);

        BranchOrchestrator.Outcome outcome = orchestrator.run(context());

        assertThat(outcome.answer()).startsWith("唯一答案");
    }

    @Test
    @DisplayName("分支抛异常：该路 FAILED，其余路照常返回，错误码为 QA_BRANCHES_INCOMPLETE")
    void failureDoesNotDropCompletedBranches() {
        BranchOrchestrator orchestrator = new BranchOrchestrator(List.of(
                fake("a", "古文", 10, BranchResult.succeeded("a", "古文",
                        BranchResult.BASIS_RETRIEVED_EVIDENCE, "原文答案",
                        List.of(citation("c1", 1)), 1)),
                new StubBranch("b", "图谱", 20, () -> {
                    throw new IllegalStateException("图库炸了");
                })
        ), executor, properties, tracing);

        BranchOrchestrator.Outcome outcome = orchestrator.run(context());

        assertThat(outcome.status()).isEqualTo(BranchOrchestrator.STATUS_PARTIAL);
        assertThat(outcome.errorCode()).isEqualTo(BranchOrchestrator.ERROR_INCOMPLETE);
        assertThat(outcome.answer()).contains("原文答案").contains("执行失败");
        assertThat(outcome.branches())
                .filteredOn(branch -> "b".equals(branch.key()))
                .singleElement()
                .satisfies(branch -> {
                    assertThat(branch.status()).isEqualTo(BranchResult.FAILED);
                    assertThat(branch.errorCode()).isEqualTo(BranchOrchestrator.ERROR_STEP_FAILED);
                });
    }

    @Test
    @DisplayName("全部无依据：INSUFFICIENT_EVIDENCE，不拼出空答案")
    void allInsufficientYieldsInsufficient() {
        BranchOrchestrator orchestrator = new BranchOrchestrator(List.of(
                fake("a", "古文", 10, BranchResult.insufficient("a", "古文", "无", 0)),
                fake("b", "图谱", 20, BranchResult.skipped("b", "图谱", "未启用", 0))
        ), executor, properties, tracing);

        BranchOrchestrator.Outcome outcome = orchestrator.run(context());

        assertThat(outcome.status()).isEqualTo(BranchOrchestrator.STATUS_INSUFFICIENT);
        assertThat(outcome.answer()).isNull();
        assertThat(outcome.citations()).isEmpty();
    }

    @Test
    @DisplayName("分支超时：该路判失败并取消，不拖住其它路")
    void slowBranchTimesOut() {
        properties.getQa().setBranchTimeout(Duration.ofMillis(150));
        BranchOrchestrator orchestrator = new BranchOrchestrator(List.of(
                fake("a", "古文", 10, BranchResult.succeeded("a", "古文",
                        BranchResult.BASIS_RETRIEVED_EVIDENCE, "原文答案",
                        List.of(citation("c1", 1)), 1)),
                new StubBranch("slow", "慢分支", 20, () -> {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return BranchResult.succeeded("slow", "慢分支",
                            BranchResult.BASIS_MODEL_KNOWLEDGE, "迟到的答案", List.of(), 1_000);
                })
        ), executor, properties, tracing);

        long started = System.currentTimeMillis();
        BranchOrchestrator.Outcome outcome = orchestrator.run(context());
        long elapsed = System.currentTimeMillis() - started;

        assertThat(elapsed).isLessThan(900);
        assertThat(outcome.status()).isEqualTo(BranchOrchestrator.STATUS_PARTIAL);
        assertThat(outcome.branches())
                .filteredOn(branch -> "slow".equals(branch.key()))
                .singleElement()
                .satisfies(branch -> {
                    assertThat(branch.status()).isEqualTo(BranchResult.FAILED);
                    assertThat(branch.errorCode()).isEqualTo(BranchOrchestrator.ERROR_TIMEOUT);
                });
        assertThat(outcome.answer()).doesNotContain("迟到的答案");
    }

    @Test
    @DisplayName("分支按 order 排序，响应顺序稳定")
    void branchesAreOrdered() {
        BranchOrchestrator orchestrator = new BranchOrchestrator(List.of(
                fake("c", "第三", 30, BranchResult.insufficient("c", "第三", "无", 0)),
                fake("a", "第一", 10, BranchResult.insufficient("a", "第一", "无", 0)),
                fake("b", "第二", 20, BranchResult.insufficient("b", "第二", "无", 0))
        ), executor, properties, tracing);

        assertThat(orchestrator.descriptors())
                .extracting(item -> item.get("key"))
                .containsExactly("a", "b", "c");
    }

    // ---------------------------------------------------------------- 辅助

    private static BranchContext context() {
        return new BranchContext("doc_1", "四君子汤", "四君子汤主治什么", null,
                List.of(), null, null, List.of());
    }

    private static Citation citation(String id, int page) {
        return new Citation(id, "引用原文", page, 0, 4, 0.9);
    }

    private static AnswerBranch fake(String key, String label, int order, BranchResult result) {
        return new StubBranch(key, label, order, () -> result);
    }

    private interface Action {
        BranchResult run();
    }

    private record StubBranch(String key, String label, int order, Action action)
            implements AnswerBranch {

        @Override
        public BranchResult answer(BranchContext context) {
            return action.run();
        }
    }
}
