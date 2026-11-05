package com.kangheng.healthos.provider.qa;

import java.util.List;

import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.spi.qa.BranchResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TcmBranchTest {

    private final TcmBranch branch = new TcmBranch();

    @Test
    @DisplayName("问题命中实体时，输出带页码与原文引用的关系")
    void rendersRelationsWithPageAndQuote() {
        GraphView graph = new GraphView("doc_1",
                List.of(node("n1", "四君子汤"), node("n2", "益气健脾")),
                List.of(new GraphView.EdgeView("e1", "n1", "n2", "功效", 0.9,
                        "四君子汤功效: 益气健脾", 1)));

        BranchResult result = branch.answer(context("四君子汤的功效是什么", graph));

        assertThat(result.status()).isEqualTo(BranchResult.SUCCEEDED);
        assertThat(result.basis()).isEqualTo(BranchResult.BASIS_RETRIEVED_EVIDENCE);
        assertThat(result.answer()).contains("四君子汤 —功效→ 益气健脾")
                .contains("第 1 页")
                .contains("四君子汤功效: 益气健脾");
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.evidenceId()).isEqualTo("e1");
            assertThat(citation.page()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("只有二元组部分重合时也算相关：问「健脾」能连到「益气健脾」")
    void matchesByBigramOverlap() {
        GraphView graph = new GraphView("doc_1",
                List.of(node("n1", "四君子汤"), node("n2", "益气健脾")),
                List.of(new GraphView.EdgeView("e1", "n1", "n2", "功效", 0.9, "原文", 2)));

        BranchResult result = branch.answer(context("健脾的方子有哪些", graph));

        assertThat(result.status()).isEqualTo(BranchResult.SUCCEEDED);
        assertThat(result.answer()).contains("益气健脾");
    }

    @Test
    @DisplayName("图上没有相关实体：判无依据，不硬凑关系")
    void unrelatedQuestionIsInsufficient() {
        GraphView graph = new GraphView("doc_1",
                List.of(node("n1", "四君子汤"), node("n2", "益气健脾")),
                List.of(new GraphView.EdgeView("e1", "n1", "n2", "功效", 0.9, "原文", 1)));

        BranchResult result = branch.answer(context("量子纠缠的退相干时间", graph));

        assertThat(result.status()).isEqualTo(BranchResult.INSUFFICIENT);
        assertThat(result.citations()).isEmpty();
    }

    @Test
    @DisplayName("文档没有图谱：判无依据并说明原因")
    void emptyGraphIsInsufficient() {
        BranchResult result = branch.answer(context("四君子汤", null));

        assertThat(result.status()).isEqualTo(BranchResult.INSUFFICIENT);
        assertThat(result.note()).contains("尚未建立");
    }

    private static BranchContext context(String question, GraphView graph) {
        return new BranchContext("doc_1", "四君子汤", question, null,
                List.of(), null, graph, List.of());
    }

    private static GraphView.NodeView node(String id, String name) {
        return new GraphView.NodeView(id, name, "FORMULA", List.of());
    }
}
