package com.kangheng.healthos.provider.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 词面 + 向量的融合排序。
 *
 * <p>它是纯函数，不碰 IO、不碰模型：给定候选、词面分与向量分，输出一个确定性的顺序。
 * 这样"检索质量"这个问题在一半上是可以单测的——留下不可测的只有 embedding 本身。
 *
 * <p><b>为什么是加权和而不是两段式（向量召回 → 词面重排）</b>：
 * 两段式会引入两个不可解释的门槛（召回留多少条、重排怎么截），而且第二段看不到
 * 被第一段丢掉的候选。本地候选集本来就小，加权和一步到位，分数还能解释：
 * "这一条的 0.62 = 0.6×向量 0.8 + 0.4×词面 0.35"。
 *
 * <p><b>为什么不直接乘</b>：乘法会让任一侧为 0 就整体为 0——同义改写（向量高、词面 0）
 * 与关键词命中（词面高、向量一般）这两种真实情况都会被误杀。
 */
public final class HybridScorer {

    private HybridScorer() {
    }

    /**
     * @param vectorScores  chunkId → 余弦相似度（已归一化到 [0,1] 之外也接受，会 clamp）
     * @param vectorWeight  0~1；超出范围会被夹到边界
     * @param vectorMinScore 词面完全没命中时，向量分的准入门槛
     */
    public static List<Hit> rank(String question,
                                 List<LocalScorer.Candidate> candidates,
                                 Map<String, Double> vectorScores,
                                 int topK,
                                 double vectorWeight,
                                 double vectorMinScore) {
        if (question == null || question.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> queryTokens = LocalScorer.tokens(question);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        double weight = Math.max(0d, Math.min(1d, vectorWeight));
        double floor = Math.max(0d, vectorMinScore);
        Map<String, Double> scores = vectorScores == null ? Map.of() : vectorScores;

        List<Hit> hits = new ArrayList<>();
        for (LocalScorer.Candidate candidate : candidates) {
            if (candidate.content() == null || candidate.content().isBlank()) {
                continue;
            }
            double lexical = coverage(queryTokens, candidate.content());
            double vector = clamp01(scores.getOrDefault(candidate.id(), 0d));
            if (lexical == 0d && vector < floor) {
                // 既不沾词、也不像，就不要拿"分数最低也是分"来凑数
                continue;
            }
            double score = round(weight * vector + (1 - weight) * lexical);
            hits.add(new Hit(candidate, score, round(lexical), round(vector)));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(Comparator.comparingDouble(Hit::vector).reversed())
                .thenComparing(hit -> hit.candidate().charStart() == null ? 0 : hit.candidate().charStart()));
        return hits.size() <= topK ? hits : new ArrayList<>(hits.subList(0, topK));
    }

    /** 问题里的二元组有多大比例出现在这段正文里。长文本不做额外惩罚——向量分已经隐含了语义密度。 */
    private static double coverage(Set<String> queryTokens, String content) {
        Set<String> contentTokens = LocalScorer.tokens(content);
        if (contentTokens.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                matched++;
            }
        }
        return (double) matched / queryTokens.size();
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    /**
     * @param score    融合分（对外展示、排序用这个）
     * @param lexical  词面覆盖率，排障时看"到底是哪一路把它顶上来的"
     * @param vector   余弦相似度
     */
    public record Hit(LocalScorer.Candidate candidate, double score, double lexical, double vector) {
    }
}
