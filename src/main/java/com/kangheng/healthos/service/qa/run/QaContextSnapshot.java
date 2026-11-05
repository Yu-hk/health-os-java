package com.kangheng.healthos.service.qa.run;

import java.util.List;

import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.model.patient.PatientMetric;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

/**
 * 提交时冻结的上下文快照。
 *
 * <p>之所以单独定义成 record 而不是直接存 Map：快照要能被 JSON 序列化再反序列化回来，
 * 而 {@code Map} 里存的对象反序列化后会变成 {@code LinkedHashMap}，
 * 强转回 {@code RetrievedChunk} 会直接 {@code ClassCastException}。
 * 用 record 明确字段类型，Jackson 才能还原成正确的类型。
 *
 * @param retrieveError 检索失败原因。非 null 时原文分支必须判失败而不是"没找到"——
 *                      "检索坏了"和"库里没有"是两件不同的事
 */
public record QaContextSnapshot(String documentTitle,
                                List<KnowledgeBase.RetrievedChunk> hits,
                                String retrieveError,
                                GraphView graph,
                                List<PatientMetric> metrics) {

    public QaContextSnapshot {
        hits = hits == null ? List.of() : List.copyOf(hits);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}
