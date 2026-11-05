package com.kangheng.healthos.service.classics;

import java.util.List;

import com.kangheng.healthos.spi.graph.MergedGraph;
import com.kangheng.healthos.spi.ai.LlmProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionMergerTest {

    private static LlmProvider.ExtractedEntity entity(String name, String type, String... aliases) {
        return new LlmProvider.ExtractedEntity(name, type, List.of(aliases));
    }

    private static LlmProvider.ExtractedRelation relation(String source, String target, String type,
                                                          double confidence, String evidence) {
        return new LlmProvider.ExtractedRelation(source, target, type, confidence, evidence);
    }

    @Test
    @DisplayName("重叠分段抽到的同一实体只保留一条并合并别名")
    void mergesDuplicateEntities() {
        var first = new LlmProvider.ExtractionResult(
                List.of(entity("四君子汤", "FORMULA", "四君汤")), List.of());
        var second = new LlmProvider.ExtractionResult(
                List.of(entity("四君子汤 ", "FORMULA", "人参汤")), List.of());

        MergedGraph merged = ExtractionMerger.merge(List.of(first, second), 0.5, 100, 100);

        assertThat(merged.entities()).hasSize(1);
        assertThat(merged.entities().get(0).aliases()).containsExactly("四君汤", "人参汤");
    }

    @Test
    @DisplayName("同名不同类型视为不同实体")
    void keepsSameNameWithDifferentType() {
        var result = new LlmProvider.ExtractionResult(
                List.of(entity("补气", "EFFECT"), entity("补气", "CONCEPT")), List.of());
        MergedGraph merged = ExtractionMerger.merge(List.of(result), 0.1, 100, 100);
        assertThat(merged.entities()).hasSize(2);
    }

    @Test
    @DisplayName("重复关系保留更高置信度，低置信度整体丢弃")
    void deduplicatesRelationsAndFiltersLowConfidence() {
        var first = new LlmProvider.ExtractionResult(List.of(),
                List.of(relation("四君子汤", "脾胃气虚", "主治", 0.6, "原文A"),
                        relation("黄芪", "气虚", "主治", 0.2, "原文B")));
        var second = new LlmProvider.ExtractionResult(List.of(),
                List.of(relation("四君子汤", "脾胃气虚", "主治", 0.92, "原文A")));

        MergedGraph merged = ExtractionMerger.merge(List.of(first, second), 0.55, 100, 100);

        assertThat(merged.relations()).hasSize(1);
        assertThat(merged.relations().get(0).confidence()).isEqualTo(0.92);
    }

    @Test
    @DisplayName("实体与关系数量受上限约束")
    void appliesLimits() {
        var result = new LlmProvider.ExtractionResult(
                List.of(entity("甲汤", "FORMULA"), entity("乙汤", "FORMULA"), entity("丙汤", "FORMULA")),
                List.of(relation("甲汤", "气虚", "主治", 0.9, "e"),
                        relation("乙汤", "气虚", "主治", 0.9, "e")));
        MergedGraph merged = ExtractionMerger.merge(List.of(result), 0.5, 2, 1);
        assertThat(merged.entities()).hasSize(2);
        assertThat(merged.relations()).hasSize(1);
    }

    @Test
    @DisplayName("空白与空名不会进入图谱")
    void skipsBlankNames() {
        var result = new LlmProvider.ExtractionResult(
                List.of(entity("  ", "FORMULA"), entity("人参", "HERB")), List.of());
        MergedGraph merged = ExtractionMerger.merge(List.of(result), 0.1, 100, 100);
        assertThat(merged.entities()).hasSize(1);
        assertThat(merged.entities().get(0).name()).isEqualTo("人参");
    }
}
