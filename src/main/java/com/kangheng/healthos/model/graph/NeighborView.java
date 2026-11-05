package com.kangheng.healthos.model.graph;

import java.util.List;

/**
 * 多跳邻域视图。
 *
 * <p>与 {@link GraphView} 的区别只在于带上了"这是几跳的结果"和"根是谁"——
 * 前端在展示时需要用它们区分"整图"和"某味药的关联子网"。
 */
public record NeighborView(String documentId, String entityId, int depth, String store,
                           List<GraphView.NodeView> nodes, List<GraphView.EdgeView> edges) {
}
