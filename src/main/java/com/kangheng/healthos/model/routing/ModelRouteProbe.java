package com.kangheng.healthos.model.routing;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一次**能力探测**的结果。
 *
 * <p>{@code fingerprint} = HMAC-SHA256(主密钥, 规范化配置 + 密钥)。
 * 它解决的问题是："这条主线当前的这份配置，探测过了吗？"
 * 换一把密钥就是另一份配置，指纹随之改变，必须重新探测——
 * 否则"上一把密钥能通"会被当成"这把密钥也能通"，那是典型的假通过。
 */
@Data
@TableName("model_route_probes")
public class ModelRouteProbe {

    @TableId(type = IdType.INPUT)
    private String id;
    private String pipelineKey;
    /** 探测时针对的配置版本号。 */
    private Integer configVersion;
    private String fingerprint;
    /** 探测结果 JSON：status / latency_ms / message / capabilities。 */
    private String result;
    private String actor;
    private OffsetDateTime createdAt;
}
