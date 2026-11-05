package com.kangheng.healthos.spi.graph;

import java.util.List;

import com.kangheng.healthos.spi.ai.LlmProvider;

/**
 * 合并后的待入库图：实体 + 关系。
 *
 * <p><b>为什么在 spi 而不在合并器里</b>：它是 {@link GraphStore#rebuild} 的入参，
 * 属于<b>图存储契约的一部分</b>。之前它作为 {@code ExtractionMerger} 的嵌套类型，
 * 导致 {@code GraphStore} 这个接口反过来依赖 {@code service.classics}——
 * 契约依赖实现，换实现就得改契约。
 *
 * <p>放在 spi 之后依赖方向是单向的：{@code service}（合并器）产出它，
 * {@code provider}（图存储实现）消费它，两边都只依赖接口层。
 */
public record MergedGraph(List<LlmProvider.ExtractedEntity> entities,
                          List<LlmProvider.ExtractedRelation> relations) {

    public MergedGraph {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    public static MergedGraph empty() {
        return new MergedGraph(List.of(), List.of());
    }
}
