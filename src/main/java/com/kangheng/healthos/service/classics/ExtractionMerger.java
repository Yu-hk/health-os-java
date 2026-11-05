package com.kangheng.healthos.service.classics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.spi.graph.EntityNames;
import com.kangheng.healthos.spi.graph.MergedGraph;

/**
 * 多分段抽取结果合并。
 *
 * <p>同一实体可能被多个重叠分段抽到，同一条关系也可能重复出现。
 * 合并规则：实体按「归一化名称 + 类型」去重并合并别名；关系按「源|目标|类型」去重，
 * 重复时保留更高的置信度；低于阈值的整体丢弃。
 *
 * <p>产出类型 {@link MergedGraph} 定义在 {@code spi.graph}——它是图存储的入参，
 * 不属于本类的私有结构。
 */
public final class ExtractionMerger {

    private ExtractionMerger() {
    }

    public static MergedGraph merge(List<LlmProvider.ExtractionResult> results,
                                    double minConfidence,
                                    int maxEntities,
                                    int maxRelations) {
        Map<String, LlmProvider.ExtractedEntity> entities = new LinkedHashMap<>();
        Map<String, LlmProvider.ExtractedRelation> relations = new LinkedHashMap<>();

        for (LlmProvider.ExtractionResult result : results) {
            if (result == null) {
                continue;
            }
            for (LlmProvider.ExtractedEntity entity : result.entities()) {
                String name = EntityNames.normalize(entity.name());
                if (name.isEmpty()) {
                    continue;
                }
                String key = key(name, entity.type());
                LlmProvider.ExtractedEntity existing = entities.get(key);
                if (existing == null) {
                    entities.put(key, new LlmProvider.ExtractedEntity(
                            entity.name().trim(), entity.type(),
                            entity.aliases() == null ? List.of() : new ArrayList<>(entity.aliases())));
                } else {
                    List<String> merged = new ArrayList<>(existing.aliases());
                    if (entity.aliases() != null) {
                        for (String alias : entity.aliases()) {
                            if (!merged.contains(alias)) {
                                merged.add(alias);
                            }
                        }
                    }
                    entities.put(key, new LlmProvider.ExtractedEntity(
                            existing.name(), existing.type(), merged));
                }
            }
            for (LlmProvider.ExtractedRelation relation : result.relations()) {
                if (relation.confidence() < minConfidence) {
                    continue;
                }
                String source = EntityNames.normalize(relation.source());
                String target = EntityNames.normalize(relation.target());
                if (source.isEmpty() || target.isEmpty()) {
                    continue;
                }
                String key = key(source, relation.type()) + ">" + key(target, "");
                LlmProvider.ExtractedRelation existing = relations.get(key);
                if (existing == null || existing.confidence() < relation.confidence()) {
                    relations.put(key, relation);
                }
            }
        }

        List<LlmProvider.ExtractedEntity> limitedEntities = entities.values().stream()
                .limit(maxEntities)
                .toList();
        List<LlmProvider.ExtractedRelation> limitedRelations = relations.values().stream()
                .limit(maxRelations)
                .toList();
        return new MergedGraph(limitedEntities, limitedRelations);
    }

    private static String key(String name, String type) {
        return (type == null ? "" : type.trim()) + "|" + name;
    }
}
