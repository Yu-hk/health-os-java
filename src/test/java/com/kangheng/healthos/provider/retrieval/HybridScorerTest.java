package com.kangheng.healthos.provider.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 融合排序的规则必须可复现、可解释。
 *
 * <p>这一段是整条向量检索里**唯一不依赖模型**的部分：给定词面分与向量分，
 * 输出顺序必须是确定的。因此模型好坏可以另说，排序逻辑本身不能含糊。
 */
class HybridScorerTest {

    private static LocalScorer.Candidate candidate(String id, String content, int charStart) {
        return new LocalScorer.Candidate(id, content, 1, charStart, charStart + content.length());
    }

    @Test
    @DisplayName("向量命中但词面完全没对上：仍要召回（同义改写的价值就在这里）")
    void vectorOnlyHitIsKept() {
        List<LocalScorer.Candidate> candidates = List.of(candidate("chk_1", "党参、白术、茯苓、甘草", 0));

        List<HybridScorer.Hit> hits = HybridScorer.rank("四君子汤由哪些药组成", candidates,
                Map.of("chk_1", 0.82), 5, 0.6, 0.45);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).lexical()).isZero();
        assertThat(hits.get(0).vector()).isEqualTo(0.82);
        // 0.6 * 0.82 + 0.4 * 0
        assertThat(hits.get(0).score()).isEqualTo(0.492);
    }

    @Test
    @DisplayName("两路都没命中：不许靠“分数最低也是分”凑数")
    void noSignalIsDropped() {
        List<LocalScorer.Candidate> candidates = List.of(candidate("chk_1", "桂枝汤调和营卫", 0));

        List<HybridScorer.Hit> hits = HybridScorer.rank("完全无关的问题", candidates,
                Map.of("chk_1", 0.2), 5, 0.6, 0.45);

        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("词面命中但向量分很低：仍保留（关键词精确匹配不能输给语义模糊）")
    void lexicalOnlyHitIsKept() {
        List<LocalScorer.Candidate> candidates = List.of(candidate("chk_1", "四君子汤主治脾胃气虚", 10));

        List<HybridScorer.Hit> hits = HybridScorer.rank("四君子汤", candidates, Map.of(), 5, 0.6, 0.45);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).lexical()).isGreaterThan(0);
        assertThat(hits.get(0).vector()).isZero();
        assertThat(hits.get(0).score()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("权重 0 时退化为纯词面：向量分只影响同分时的次序")
    void zeroWeightFallsBackToLexical() {
        List<LocalScorer.Candidate> candidates = List.of(
                candidate("chk_lex", "四君子汤", 0),
                candidate("chk_vec", "党参白术茯苓甘草", 100));

        List<HybridScorer.Hit> hits = HybridScorer.rank("四君子汤", candidates,
                Map.of("chk_vec", 0.99), 5, 0.0, 0.0);

        assertThat(hits.get(0).candidate().id()).isEqualTo("chk_lex");
        assertThat(hits.get(0).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("同分时按向量分、再按偏移排序：结果必须确定")
    void tieBreakIsDeterministic() {
        List<LocalScorer.Candidate> candidates = List.of(
                candidate("chk_b", "黄芪补气", 200),
                candidate("chk_a", "黄芪补气", 100));

        List<HybridScorer.Hit> first = HybridScorer.rank("黄芪", candidates, Map.of(), 5, 0.6, 0.45);
        List<HybridScorer.Hit> second = HybridScorer.rank("黄芪", List.of(candidates.get(1), candidates.get(0)),
                Map.of(), 5, 0.6, 0.45);

        assertThat(first).extracting(hit -> hit.candidate().id()).containsExactly("chk_a", "chk_b");
        assertThat(second).extracting(hit -> hit.candidate().id()).containsExactly("chk_a", "chk_b");
    }

    @Test
    @DisplayName("topK 截断，且空查询/空候选不报错")
    void topKAndEmptyInputs() {
        List<LocalScorer.Candidate> candidates = List.of(
                candidate("chk_1", "四君子汤", 0),
                candidate("chk_2", "四君子汤加减", 50),
                candidate("chk_3", "四君子汤方解", 90));

        assertThat(HybridScorer.rank("四君子汤", candidates, Map.of(), 2, 0.6, 0.45)).hasSize(2);
        assertThat(HybridScorer.rank("  ", candidates, Map.of(), 2, 0.6, 0.45)).isEmpty();
        assertThat(HybridScorer.rank("四君子汤", List.of(), Map.of(), 2, 0.6, 0.45)).isEmpty();
    }

    @Test
    @DisplayName("超出 [0,1] 的向量分被夹住，NaN 当 0 处理")
    void vectorScoreIsClamped() {
        List<LocalScorer.Candidate> candidates = List.of(candidate("chk_1", "四君子汤", 0));

        assertThat(HybridScorer.rank("四君子汤", candidates, Map.of("chk_1", 3.0), 5, 1.0, 0.0)
                .get(0).score()).isEqualTo(1.0);
        assertThat(HybridScorer.rank("四君子汤", candidates, Map.of("chk_1", Double.NaN), 5, 1.0, 0.0)
                .get(0).score()).isEqualTo(0.0);
    }
}
