package com.kangheng.healthos.model.classics;

import java.util.List;

/**
 * 流水线节点定义，顺序即执行顺序。
 *
 * <p>与原项目 {@code NODE_SPECS} 一一对应，代码值保持英文以便和日志/接口兼容。
 */
public enum PipelineNodeSpec {

    NORMALIZE_OR_OCR("normalize_or_ocr", "1 · OCR / Markdown 归一化"),
    TCM_EXTRACT("tcm_extract", "2A · 医学典籍实体关系抽取"),
    RAGFLOW_INDEX("ragflow_index", "2B · 检索切片索引"),
    GRAPH_BUILD("graph_build", "3A · 医学典籍知识图谱"),
    PIPELINE_MANIFEST("pipeline_manifest", "4 · Pipeline Manifest");

    private final String code;
    private final String label;

    PipelineNodeSpec(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public int orderIndex() {
        return ordinal() + 1;
    }

    public static List<PipelineNodeSpec> ordered() {
        return List.of(values());
    }
}
