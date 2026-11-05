package com.kangheng.healthos.model.auth;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一条已吊销的会话。
 *
 * <p>生命周期天生等同于令牌剩余寿命（最长一个 TTL）：令牌都过期了，
 * 这条记录再留着就只是垃圾。所以 {@code expiresAt} 不是装饰，是清理依据——
 * 没有它就只能按 {@code revokedAt} 猜一个保守期限，越猜越不敢删。
 */
@Data
@TableName("auth_session_revocation")
public class AuthSessionRevocation {

    @TableId(type = IdType.INPUT)
    private String sessionId;

    private String subject;

    private OffsetDateTime expiresAt;

    private OffsetDateTime revokedAt;
}
