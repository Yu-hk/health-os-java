package com.kangheng.healthos.provider.qa;

import java.util.ArrayList;
import java.util.List;

import com.kangheng.healthos.model.qa.Citation;
import com.kangheng.healthos.spi.ai.LlmException;
import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.spi.qa.AnswerBranch;
import com.kangheng.healthos.spi.qa.BranchContext;
import com.kangheng.healthos.spi.qa.BranchResult;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

import org.springframework.stereotype.Component;

/**
 * 原文证据分支（对应原工程 {@code ragflow} 路）：只用知识库召回片段作答，逐条挂引用。
 *
 * <p>这一路是整个问答的"底线"：它的引用必须能回溯到原文偏移，
 * 所以宁可返回 INSUFFICIENT_EVIDENCE 也不允许用模型常识补全。
 */
@Component
public class EvidenceBranch implements AnswerBranch {

    private static final String KEY = "ragflow";
    private static final String LABEL = "古籍原文";

    private final LlmProvider llmProvider;

    public EvidenceBranch(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

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
        return 10;
    }

    @Override
    public BranchResult answer(BranchContext context) {
        long started = System.nanoTime();
        if (context.retrieveError() != null) {
            // 检索本身坏了 ≠ 库里没内容，必须报错而不是"无依据"
            return BranchResult.failed(KEY, LABEL, "QA_RETRIEVAL_FAILED",
                    context.retrieveError(), elapsed(started));
        }
        if (context.hits().isEmpty()) {
            return BranchResult.insufficient(KEY, LABEL, "知识库未召回与问题相关的片段", elapsed(started));
        }
        StringBuilder block = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        int index = 1;
        for (KnowledgeBase.RetrievedChunk hit : context.hits()) {
            String content = hit.content().strip();
            block.append('[').append(index++).append("] ").append(content).append('\n');
            citations.add(new Citation(hit.chunkId(), content, hit.page(),
                    hit.charStart(), hit.charEnd(), hit.score(), hit.documentId()));
        }
        String answer;
        try {
            answer = llmProvider.answer(
                    LlmProvider.RouteContext.of("PATIENT_QA", "qa_document",
                            context.documentId() == null ? "unknown" : context.documentId()),
                    context.question(), block.toString());
        } catch (LlmException ex) {
            return BranchResult.failed(KEY, LABEL, "QA_MODEL_FAILED", ex.getMessage(), elapsed(started));
        }
        if (answer == null || answer.isBlank()) {
            return BranchResult.failed(KEY, LABEL, "QA_EMPTY_ANSWER", "模型返回空答案", elapsed(started));
        }
        return BranchResult.succeeded(KEY, LABEL, BranchResult.BASIS_RETRIEVED_EVIDENCE,
                answer, citations, elapsed(started));
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
