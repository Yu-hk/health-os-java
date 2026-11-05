package com.kangheng.healthos.model.auth;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 一个账号的当前登录失败状态（一行 = 一个账号，不是一行 = 一次失败）。
 *
 * <p>不存失败明细：要回答的只是"现在锁没锁、还能试几次"，存明细就得每次聚合，
 * 而聚合的结果一旦和明细不同步，锁定判定就不可信了。
 *
 * <p>{@code usernameKey} 是 normalize 后的小写用户名——与内存实现同一个 key，
 * 否则「Admin」和「admin」会各占一行，阈值等于翻倍。
 */
@Data
@TableName("auth_login_attempt")
public class AuthLoginAttemptRow {

    @TableId(type = IdType.INPUT)
    private String usernameKey;

    private Integer failCount;

    private OffsetDateTime windowStart;

    private OffsetDateTime lockedUntil;

    private OffsetDateTime updatedAt;
}
