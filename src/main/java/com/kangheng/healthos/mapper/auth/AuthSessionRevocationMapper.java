package com.kangheng.healthos.mapper.auth;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.auth.AuthSessionRevocation;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话吊销名单。
 *
 * <p>只需要主键读写 + 一次按到期时间的清理，其余用 {@link BaseMapper} 就够，
 * 不给一张"只会追加和核对"的表配多余的方法。
 */
@Mapper
public interface AuthSessionRevocationMapper extends BaseMapper<AuthSessionRevocation> {

    /**
     * 清掉令牌本身已经过期的吊销记录。
     *
     * <p>按 {@code expires_at} 删而不是按 {@code revoked_at} 删：吊销时刻与令牌寿命无关，
     * 按吊销时间清会把"还剩一小时才过期"的记录提前删掉，等于把登出忘了。
     */
    @Delete("DELETE FROM auth_session_revocation WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
