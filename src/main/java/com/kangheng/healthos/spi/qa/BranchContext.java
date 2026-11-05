package com.kangheng.healthos.spi.qa;

import java.util.List;

import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

/**
 * 一次问答共享的上下文：<b>检索、取图、取指标都只做一遍</b>，三路分支各自读取。
 *
 * <p>为什么不放进分支里各查一次：三路并行时若各自打一次 RAGFlow，
 * 单次问答的检索开销会变成 3 倍，且三路看到的证据可能不一致（外部服务有抖动）。
 * 统一预取后，三路看到的是同一份事实，差异只体现在"怎么解读"。
 *
 * @param retrieveError 预取检索失败的原因。非 null 时原文分支必须判失败而不是"没找到"——
 *                      "检索坏了"和"库里没有"是两件不同的事，不能混为一谈
 */
public record BranchContext(String documentId, String documentTitle, String question, String patientId,
                            List<KnowledgeBase.RetrievedChunk> hits, String retrieveError,
                            GraphView graph, List<PatientMetric> metrics) {

    public BranchContext {
        hits = hits == null ? List.of() : List.copyOf(hits);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}
