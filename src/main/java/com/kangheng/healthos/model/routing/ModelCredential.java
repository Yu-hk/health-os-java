package com.kangheng.healthos.model.routing;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一版**加密后的**模型密钥。
 *
 * <p>密钥不进配置 JSON、不进接口响应、不进日志——它只以密文形式落库，
 * 解密只在"要真的调供应商"的那一刻发生，且解出来的值不写回任何表。
 *
 * <p>{@code (pipelineKey, version)} 唯一：一条主线的第 N 版密钥只属于第 N 版配置。
 * 配置回滚到旧版本时连同它的旧密钥一起回滚——版本与密钥必须成对，否则
 * "回到上一个能用的配置"根本回不去。
 */
@Data
@TableName("model_credentials")
public class ModelCredential {

    @TableId(type = IdType.INPUT)
    private String id;
    private String pipelineKey;
    /** 该主线内的密钥版本号。 */
    private Integer version;
    /** 密文（含 IV 与认证标签），Base64 文本。 */
    private String ciphertext;
    /** 密钥标识，便于日后轮换主密钥时分辨是哪一把。 */
    private String keyId;
    /** active / revoked。停用的密钥不能被回滚重新启用。 */
    private String status;
    private OffsetDateTime createdAt;
}
