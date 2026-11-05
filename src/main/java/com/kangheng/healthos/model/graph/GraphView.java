package com.kangheng.healthos.model.graph;

import java.util.List;

/** 图谱视图：节点 + 边 + 边上的证据引用。 */
public record GraphView(String documentId, List<NodeView> nodes, List<EdgeView> edges) {

    public record NodeView(String id, String name, String type, List<String> aliases) {
    }

    public record EdgeView(String id, String sourceId, String targetId, String type,
                           double confidence, String evidenceQuote, Integer pageFrom) {
    }
}
