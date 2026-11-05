package com.kangheng.healthos.provider.qa;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kangheng.healthos.model.qa.Citation;
import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.spi.qa.AnswerBranch;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.spi.qa.BranchResult;

import org.springframework.stereotype.Component;

/**
 * 中医图谱分支（对应原工程 {@code tcm} 路）：沿实体关系走一遍，回答"这个方和什么有关、凭什么"。
 *
 * <p>与原文分支的区别：原文分支给的是连续段落，图谱分支给的是<b>端点明确的关系</b>
 * （{@code 方剂 —功效→ 证候}），并且每条关系都带着抽取时绑定的原文引用与页码。
 * 因此它不会"编"出文档里没有的搭配——关系本身就是抽取阶段的产物。
 *
 * <p>刻意不调用模型：把关系压成自由文本只会让可核对的信息变模糊。
 * 这一路输出的是结构化事实的直读结果，页数与引用原样保留。
 */
@Component
public class TcmBranch implements AnswerBranch {

    private static final String KEY = "tcm";
    private static final String LABEL = "中医图谱";
    private static final int MAX_RELATIONS = 8;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public BranchResult answer(BranchContext context) {
        long started = System.nanoTime();
        GraphView graph = context.graph();
        if (graph == null || graph.edges().isEmpty()) {
            return BranchResult.insufficient(KEY, LABEL, "该文档尚未建立可用图谱", elapsed(started));
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (GraphView.NodeView node : graph.nodes()) {
            names.put(node.id(), node.name());
        }
        List<GraphView.EdgeView> relevant = new ArrayList<>();
        for (GraphView.EdgeView edge : graph.edges()) {
            String source = names.getOrDefault(edge.sourceId(), "");
            String target = names.getOrDefault(edge.targetId(), "");
            if (relevant(source, context.question()) || relevant(target, context.question())) {
                relevant.add(edge);
            }
        }
        if (relevant.isEmpty()) {
            return BranchResult.insufficient(KEY, LABEL,
                    "图谱中没有与问题实体相连的关系", elapsed(started));
        }

        StringBuilder answer = new StringBuilder("图谱路径（关系由原文抽取，括号内为出处）：\n");
        List<Citation> citations = new ArrayList<>();
        int index = 0;
        for (GraphView.EdgeView edge : relevant) {
            if (index >= MAX_RELATIONS) {
                break;
            }
            index++;
            String source = names.getOrDefault(edge.sourceId(), edge.sourceId());
            String target = names.getOrDefault(edge.targetId(), edge.targetId());
            answer.append("· ").append(source).append(" —").append(edge.type()).append("→ ")
                    .append(target);
            if (edge.pageFrom() != null) {
                answer.append("（第 ").append(edge.pageFrom()).append(" 页）");
            }
            if (edge.evidenceQuote() != null && !edge.evidenceQuote().isBlank()) {
                answer.append("：").append(edge.evidenceQuote().strip());
            }
            answer.append('\n');
            citations.add(new Citation(edge.id(), edge.evidenceQuote(), edge.pageFrom(),
                    null, null, edge.confidence()));
        }
        answer.append("共 ").append(index).append(" 条关系，均可在原文中按页码回溯。");
        return BranchResult.succeeded(KEY, LABEL, BranchResult.BASIS_RETRIEVED_EVIDENCE,
                answer.toString(), citations, elapsed(started));
    }

    /**
     * 实体与问题是否相关：包含关系 + 中文二元组重合。
     *
     * <p>只做确定性匹配，不做同义词扩展——"脾"和"脾胃"是否算同一个概念，
     * 交给人判断比交给规则猜更安全。
     */
    private static boolean relevant(String name, String question) {
        if (name == null || name.isBlank() || question == null || question.isBlank()) {
            return false;
        }
        if (question.contains(name) || name.contains(question)) {
            return true;
        }
        if (name.length() < 2) {
            return false;
        }
        Set<String> nameBigrams = new HashSet<>();
        for (int i = 0; i + 2 <= name.length(); i++) {
            nameBigrams.add(name.substring(i, i + 2));
        }
        for (int i = 0; i + 2 <= question.length(); i++) {
            if (nameBigrams.contains(question.substring(i, i + 2))) {
                return true;
            }
        }
        return false;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
