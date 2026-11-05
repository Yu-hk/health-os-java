package com.kangheng.healthos.model.usage;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次模型调用的用量与成本。
 *
 * <p><b>一行 = 一次调用</b>，不做「日聚合表 + 明细表」两套：明细保留下来才能回答
 * "昨天那条主线的成本是哪次重试抬上去的"，聚合随时可以算。等明细真的多到影响查询时
 * 再加物化视图，那时也知道该按什么维度聚合。
 *
 * <p><b>{@code costMicros} 允许为 NULL</b>：没配单价的模型不猜价格。猜出来的成本
 * 比没有成本更糟——它会让"总花费"看起来是个准确数字，而其中一部分是编的。
 * 汇总时 NULL 不计入求和，同时用 {@code unpricedCalls} 把"有多少次没算钱"暴露出来。
 */
@Data
@TableName("model_usage")
public class ModelUsageRecord {

    @TableId(type = IdType.INPUT)
    private String id;
    /** 成本归属：主线（pipelineKey）。配额按它统计。 */
    private String scopeKey;
    private String providerId;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    /**
     * 命中上游前缀缓存的输入 token 数（<b>含在</b> {@code promptTokens} 内，不是额外增量）。
     *
     * <p>落这一列是为了让 {@code costMicros} 可复核：缓存价与未命中价能差 50 倍，
     * 只留 prompt/completion 的话，事后没人能复算出成本是怎么来的。
     */
    private Integer cachedPromptTokens;
    /** 微元（1 元 = 1_000_000）。单价未知时为 NULL。 */
    private Long costMicros;
    private String currency;
    /** 关联轨迹，便于从一笔花费回到那次调用。可为空。 */
    private String traceId;
    private OffsetDateTime createdAt;
}
