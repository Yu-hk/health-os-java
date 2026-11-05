package com.kangheng.healthos.model.routing;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一条业务主线的**当前版本指针**。
 *
 * <p>只记"现在是第几版"，真正的配置在 {@link ModelRouteVersion} 里。
 * 拆成两张表的理由和记忆域一样：指针是可变的当前状态，版本是不可变的历史，
 * 混在一张表里就会出现"历史被就地改掉"这种说不清的情况。
 *
 * <p>{@code lastProbeAt} 用于探测限流——探测要真的打一次外部供应商，
 * 不限流的话配置页能被刷出一堆无意义的外部调用。
 */
@Data
@TableName("model_routes")
public class ModelRoute {

    /** 业务主线标识，如 {@code REPORT}。 */
    @TableId(type = IdType.INPUT)
    private String pipelineKey;
    /** 当前生效的配置版本号。 */
    private Integer version;
    /** 上次探测时间，用于 10 秒限流。 */
    private OffsetDateTime lastProbeAt;
    private OffsetDateTime updatedAt;
}
