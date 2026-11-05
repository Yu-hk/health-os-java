package com.kangheng.healthos.provider.retrieval;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地打分器的单元测试。
 *
 * <p>打分是纯函数，所以断言可以做到"给同样输入必得同样输出"——
 * 这是它敢做 RAGFlow 兜底的前提：排障时能确定问题出在召回还是生成。
 */
class LocalScorerTest {

    private static LocalScorer.Candidate candidate(String id, String content, int start) {
        return new LocalScorer.Candidate(id, content, 1, start, start + content.length());
    }

    @Test
    @DisplayName("中英混排的文本按二元组 + 小写英文词切开")
    void tokenizesMixedText() {
        Set<String> tokens = LocalScorer.tokens("人参 ABC12 补气");
        // 中文取相邻二元组，英文/数字整串小写化
        assertThat(tokens).contains("人参", "abc12", "补气");
        // 单字也能成词，否则"参"这种一字查询永远召回不到
        assertThat(LocalScorer.tokens("参")).containsExactly("参");
        assertThat(LocalScorer.tokens("")).isEmpty();
        assertThat(LocalScorer.tokens(null)).isEmpty();
    }

    @Test
    @DisplayName("命中率高的候选排在前面")
    void ranksRelevantCandidateFirst() {
        List<LocalScorer.Candidate> candidates = List.of(
                candidate("a", "人参味甘，补气健脾。", 0),
                candidate("b", "四君子汤主治脾胃气虚，症见面色萎白、食少便溏。", 20),
                candidate("c", "甘草调和诸药。", 60));

        List<LocalScorer.Hit> hits = LocalScorer.rank("四君子汤主治什么", candidates, 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).candidate().id()).isEqualTo("b");
        assertThat(hits.get(0).score()).isGreaterThan(0d);
    }

    @Test
    @DisplayName("topK 是硬上限，空入参直接返回空")
    void respectsTopK() {
        List<LocalScorer.Candidate> candidates = List.of(
                candidate("a", "人参补气健脾", 0),
                candidate("b", "人参养荣汤", 10),
                candidate("c", "甘草", 20));

        assertThat(LocalScorer.rank("人参补气", candidates, 2)).hasSize(2);
        assertThat(LocalScorer.rank("人参补气", List.of(), 2)).isEmpty();
        assertThat(LocalScorer.rank("", candidates, 2)).isEmpty();
        assertThat(LocalScorer.rank(null, candidates, 2)).isEmpty();
    }

    @Test
    @DisplayName("一个词都对不上就返回空，不拿低分结果凑数")
    void returnsEmptyWhenNothingMatches() {
        List<LocalScorer.Candidate> candidates = List.of(
                candidate("a", "人参味甘，补气健脾。", 0),
                candidate("b", "四君子汤主治脾胃气虚。", 20));

        assertThat(LocalScorer.rank("量子纠缠的退相干时间", candidates, 3)).isEmpty();
    }

    @Test
    @DisplayName("同样输入必得同样分数（可复现）")
    void scoringIsDeterministic() {
        List<LocalScorer.Candidate> candidates = List.of(
                candidate("a", "四君子汤", 0),
                candidate("b", "四君子汤加减", 10));

        double first = LocalScorer.rank("四君子汤", candidates, 1).get(0).score();
        double second = LocalScorer.rank("四君子汤", candidates, 1).get(0).score();

        assertThat(first).isEqualTo(second);
    }
}
