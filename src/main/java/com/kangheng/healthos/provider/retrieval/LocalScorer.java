package com.kangheng.healthos.provider.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地召回打分器：中文二元组 + 英文词的集合重合度。
 *
 * <p><b>为什么在 provider 而不是 service</b>：它是 {@link LocalKnowledgeBase} 的
 * <b>实现细节</b>——换成本地向量检索时这一段整体被替换，而 {@code KnowledgeBase}
 * 契约不变。放在 {@code service} 会让实现类反过来依赖业务层（它之前就 import 了
 * {@code service.retrieval.RetrievalService}），形成"实现 → 消费者"的隐式回环。
 *
 * <p>打分是纯函数，便于单元测试与结果复现；升级为向量检索时把 {@link #rank}
 * 换成按 pgvector 距离排序即可，上层（{@code QaService}、分支、run）全都不用动。
 */
public final class LocalScorer {

    private LocalScorer() {
    }

    public record Candidate(String id, String content, Integer page, Integer charStart, Integer charEnd) {
    }

    public record Hit(Candidate candidate, double score) {
    }

    public static List<Hit> rank(String question, List<Candidate> candidates, int topK) {
        if (question == null || question.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> queryTokens = tokens(question);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.content() == null || candidate.content().isBlank()) {
                continue;
            }
            Set<String> contentTokens = tokens(candidate.content());
            if (contentTokens.isEmpty()) {
                continue;
            }
            int matched = 0;
            for (String token : queryTokens) {
                if (contentTokens.contains(token)) {
                    matched++;
                }
            }
            if (matched == 0) {
                continue;
            }
            // 命中率为主，正文长度做轻微惩罚，避免长段落靠体量刷分
            double coverage = (double) matched / queryTokens.size();
            double lengthPenalty = 1.0 / (1.0 + Math.log1p(contentTokens.size()) / 10.0);
            hits.add(new Hit(candidate, round(coverage * (0.9 + 0.1 * lengthPenalty))));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(hit -> hit.candidate().charStart() == null ? 0 : hit.candidate().charStart()));
        return hits.size() <= topK ? hits : hits.subList(0, topK);
    }

    /** 中文取二元组（单字词退化为单字），英文/数字按连续串取小写词。 */
    public static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) {
            return result;
        }
        StringBuilder ascii = new StringBuilder();
        List<Character> cjk = new ArrayList<>();
        for (char ch : text.toCharArray()) {
            if (isAsciiWord(ch)) {
                ascii.append(Character.toLowerCase(ch));
                flushCjk(cjk, result);
                continue;
            }
            if (!ascii.isEmpty()) {
                if (ascii.length() >= 2) {
                    result.add(ascii.toString());
                }
                ascii.setLength(0);
            }
            if (isCjk(ch)) {
                cjk.add(ch);
            } else {
                flushCjk(cjk, result);
            }
        }
        if (ascii.length() >= 2) {
            result.add(ascii.toString());
        }
        flushCjk(cjk, result);
        return result;
    }

    private static void flushCjk(List<Character> buffer, Set<String> result) {
        if (buffer.isEmpty()) {
            return;
        }
        if (buffer.size() == 1) {
            result.add(String.valueOf(buffer.get(0)));
        } else {
            for (int i = 0; i + 1 < buffer.size(); i++) {
                result.add("" + buffer.get(i) + buffer.get(i + 1));
            }
        }
        buffer.clear();
    }

    private static boolean isAsciiWord(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_';
    }

    private static boolean isCjk(char ch) {
        return ch >= '\u4e00' && ch <= '\u9fff';
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
