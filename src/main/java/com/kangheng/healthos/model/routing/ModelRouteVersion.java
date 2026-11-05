package com.kangheng.healthos.model.routing;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一版**不可变**的模型路由配置。
 *
 * <p>保存与回滚都只追加新版本，绝不原地改老版本。所以"上周三这条主线用的是哪个模型、
 * 谁改的、为什么改"永远查得到——这是配置中心存在的意义，也是医疗系统里
 * "出了问题要能追溯"的基本要求。
 */
@Data
@TableName("model_route_versions")
public class ModelRouteVersion {

    @TableId(type = IdType.INPUT)
    private String id;
    private String pipelineKey;
    private Integer version;
    /** 配置 JSON：provider/model/protocol/base_url/connection_mode/parameters/fault_policy。 */
    private String config;
    /** 引用的凭据 ID（可能为空：允许暂不配密钥，但此时不能真正发起调用）。 */
    private String credentialRef;
    /** 引用的凭据版本号，与 {@code credentialRef} 配套校验。 */
    private Integer credentialVersion;
    private String actor;
    private String changeReason;
    private OffsetDateTime createdAt;
}
