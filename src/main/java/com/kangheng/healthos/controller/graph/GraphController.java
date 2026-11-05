package com.kangheng.healthos.controller.graph;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.model.graph.GraphView;
import com.kangheng.healthos.model.graph.NeighborView;
import com.kangheng.healthos.service.graph.GraphService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图谱接口。
 *
 * <p>{@code /graph} 返回整图，{@code /graph/neighbors} 返回以某实体为根的子图。
 * 后者需要真实的图遍历能力：PostgreSQL 模式下是内存双向 BFS，Neo4j 模式下是变长路径查询。
 */
@RestController
@RequestMapping("/api/v1/classics/{documentId}/graph")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping
    public GraphView graph(@CurrentUser String username, @PathVariable String documentId) {
        return graphService.graph(documentId);
    }

    /**
     * @param depth 跳数，会被存储实现裁剪到 [1, healthos.graph.neo4j.max-depth]。
     *              越界不报错而是裁剪，避免前端传个大数就把图库拖垮。
     */
    @GetMapping("/neighbors")
    public NeighborView neighbors(@CurrentUser String username,
                                  @PathVariable String documentId,
                                  @RequestParam("entityId") String entityId,
                                  @RequestParam(value = "depth", defaultValue = "1") int depth) {
        GraphView view = graphService.neighbors(documentId, entityId, depth);
        return new NeighborView(documentId, entityId, depth, graphService.storeName(),
                view.nodes(), view.edges());
    }
}
