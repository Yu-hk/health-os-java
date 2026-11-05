package com.kangheng.healthos.mapper.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.kangheng.healthos.model.auth.AuthLoginAttemptRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 登录失败计数。
 *
 * <p>核心方法是 {@link #recordFailure}：用一条 {@code INSERT ... ON CONFLICT ... RETURNING}
 * 把"读当前状态 → 判断窗口 → 累加 → 判是否锁定 → 写回"压成一次原子操作。
 *
 * <p><b>为什么不能"先 select 再 update"</b>：多实例下两个节点同时收到同一个账号的失败请求，
 * 各自读到 count=4 然后都写回 5——实际已经失败 6 次却只记了 5 次，
 * 阈值从那一刻起就偏了；更糟的是它偏得没有规律，事后查日志也对不上。
 */
@Mapper
public interface AuthLoginAttemptMapper extends BaseMapper<AuthLoginAttemptRow> {

    /**
     * 记一次失败，返回累加后的状态（含锁定判定）。
     *
     * <p>窗口内累加、超窗口归零。
     *
     * <p><b>必须是一条语句</b>：曾经写成"CTE 里 upsert + 外层 UPDATE 补 locked_until"，
     * 结果 {@code fail_count} 一路涨到 8 而 {@code locked_until} 始终为空——
     * PostgreSQL 不允许同一条语句里对同一行改两次，第二次修改不报错、直接不生效。
     * 这类 bug 不会在编译期或单测（mock 掉 SQL）里暴露，只能靠真库跑出来。
     * 所以这里宁可把累加表达式写两遍，也不要跨语句拼装。
     *
     * @param usernameKey   normalize 后的小写用户名
     * @param now           当前时刻
     * @param windowSeconds 滑动窗口长度（秒）
     * @param maxAttempts   达到即锁定
     * @param lockSeconds   锁定时长（秒）
     */
    @Select("""
            INSERT INTO auth_login_attempt AS a
                (username_key, fail_count, window_start, locked_until, updated_at)
            VALUES (
                #{usernameKey}, 1, #{now},
                CASE WHEN 1 >= #{maxAttempts}
                     THEN #{now} + #{lockSeconds} * INTERVAL '1 second' ELSE NULL END,
                #{now})
            ON CONFLICT (username_key) DO UPDATE SET
                fail_count = CASE
                    WHEN a.window_start + #{windowSeconds} * INTERVAL '1 second' > #{now}
                    THEN a.fail_count + 1 ELSE 1 END,
                window_start = CASE
                    WHEN a.window_start + #{windowSeconds} * INTERVAL '1 second' > #{now}
                    THEN a.window_start ELSE #{now} END,
                locked_until = CASE
                    WHEN (CASE
                              WHEN a.window_start + #{windowSeconds} * INTERVAL '1 second' > #{now}
                              THEN a.fail_count + 1 ELSE 1 END) >= #{maxAttempts}
                    THEN #{now} + #{lockSeconds} * INTERVAL '1 second'
                    ELSE NULL END,
                updated_at = #{now}
            RETURNING username_key, fail_count, window_start, locked_until, updated_at
            """)
    AuthLoginAttemptRow recordFailure(@Param("usernameKey") String usernameKey,
                                      @Param("now") java.time.OffsetDateTime now,
                                      @Param("windowSeconds") long windowSeconds,
                                      @Param("maxAttempts") int maxAttempts,
                                      @Param("lockSeconds") long lockSeconds);

    /** 读当前状态；没有记录返回 {@code null}（= 一次都没失败过）。 */
    @Select("SELECT * FROM auth_login_attempt WHERE username_key = #{usernameKey}")
    AuthLoginAttemptRow find(@Param("usernameKey") String usernameKey);

    /**
     * 清掉已经没有任何意义的记录：锁定已到期，或窗口已滑过去且从未锁定。
     *
     * <p>不清理的话，表会按"历史上失败过的用户名总数"无限增长——攻击者用随机用户名刷，
     * 就能把这张表撑大。清理条件必须与判定条件一致，否则清掉的记录会让阈值重新开始算。
     */
    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM auth_login_attempt
            WHERE (locked_until IS NOT NULL AND locked_until <= #{now})
               OR (locked_until IS NULL
                   AND window_start + #{windowSeconds} * INTERVAL '1 second' <= #{now})
            """)
    int deleteStale(@Param("now") java.time.OffsetDateTime now,
                    @Param("windowSeconds") long windowSeconds);
}
