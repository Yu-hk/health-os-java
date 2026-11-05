package com.kangheng.healthos.mapper.usage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.usage.ModelUsageRecord;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用量明细的数据访问。
 *
 * <p>两条聚合走 SQL 而不是"把当天的行拉回来在 Java 里加"：配额闸门在每次模型调用前
 * 都要问一次，拿全量行来求和会把一次调用变成一次全表扫描。
 *
 * <p>别名必须加双引号：PostgreSQL 把未加引号的标识符折成小写，
 * {@code AS promptTokens} 出来的键是 {@code prompttokens}，取的时候会静默拿到 null。
 */
@Mapper
public interface ModelUsageMapper extends BaseMapper<ModelUsageRecord> {

    /** 某个主线从 {@code since} 起的累计用量（配额闸门用）。 */
    @Select("""
            SELECT COALESCE(SUM(total_tokens), 0)      AS tokens,
                   COALESCE(SUM(cost_micros), 0)       AS "costMicros",
                   COUNT(*)                            AS calls,
                   COUNT(*) FILTER (WHERE cost_micros IS NULL) AS "unpricedCalls"
              FROM model_usage
             WHERE scope_key = #{scope}
               AND created_at >= #{since}
            """)
    Map<String, Object> sumScopeSince(@Param("scope") String scope,
                                      @Param("since") OffsetDateTime since);

    /** 按模型聚合（成本看板用）。 */
    @Select("""
            SELECT model                                        AS model,
                   COUNT(*)                                     AS calls,
                   COALESCE(SUM(prompt_tokens), 0)              AS "promptTokens",
                   COALESCE(SUM(completion_tokens), 0)          AS "completionTokens",
                   COALESCE(SUM(cached_prompt_tokens), 0)       AS "cachedPromptTokens",
                   COALESCE(SUM(total_tokens), 0)               AS "totalTokens",
                   COALESCE(SUM(cost_micros), 0)                AS "costMicros",
                   COUNT(*) FILTER (WHERE cost_micros IS NULL)  AS "unpricedCalls"
              FROM model_usage
             WHERE created_at >= #{since}
             GROUP BY model
             ORDER BY COALESCE(SUM(total_tokens), 0) DESC
            """)
    List<Map<String, Object>> summaryByModelSince(@Param("since") OffsetDateTime since);

    /** 按主线聚合（成本看板用）。 */
    @Select("""
            SELECT scope_key                                    AS "scopeKey",
                   COUNT(*)                                     AS calls,
                   COALESCE(SUM(total_tokens), 0)               AS "totalTokens",
                   COALESCE(SUM(cost_micros), 0)                AS "costMicros",
                   COUNT(*) FILTER (WHERE cost_micros IS NULL)  AS "unpricedCalls"
              FROM model_usage
             WHERE created_at >= #{since}
             GROUP BY scope_key
             ORDER BY COALESCE(SUM(cost_micros), 0) DESC
            """)
    List<Map<String, Object>> summaryByScopeSince(@Param("since") OffsetDateTime since);
}
