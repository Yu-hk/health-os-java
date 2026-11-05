package com.kangheng.healthos.model.routing;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 业务实例与模型配置版本的绑定关系。
 *
 * <p>一行 = "这个业务实例第一次调模型时用的是哪一版配置"。之后管理员再改路由，
 * 这个实例仍按这一版跑完，不会前后半段用两个模型。
 *
 * <p><b>这里绝不存密钥</b>，只有引用；密钥执行时按引用从 {@code model_credentials} 解密。
 */
@Data
@TableName("model_route_snapshot")
public class ModelRouteSnapshot {

    /** 主键用 {@code bizType + ":" + bizId}，天然幂等，不需要额外唯一索引。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String bizType;

    private String bizId;

    private String pipelineKey;

    private String snapshotJson;

    private OffsetDateTime createdAt;
}
