package com.kangheng.healthos.service.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.kangheng.healthos.model.auth.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话吊销名单：登出即失效。
 *
 * <p>无状态令牌的代价是"发出去就收不回"。这里记一份 {@code jti -> 原到期时间} 的名单，
 * 请求进来时比对一次，命中就当作未登录。
 *
 * <p><b>为什么是内存而不是表</b>：吊销记录的生命周期天生等同于令牌剩余寿命（最长一个 TTL），
 * 为它建表就要同时配清理任务、迁移脚本和跨节点一致性，收益不成比例。代价是重启后名单清空、
 * 进程级不共享——单实例部署下可接受，集群化时要换成共享存储，这一点写在 README 的差异清单里，
 * 不含糊过去。
 *
 * <p>容量不会无限增长：每条记录都带原到期时间，过期即失效，超过阈值时顺带清理一轮。
 */
@Component
public class SessionRevocationRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationRegistry.class);

    /** 超过这个条数就顺手清一轮过期记录，避免只在写路径上堆积。 */
    private static final int SWEEP_THRESHOLD = 256;

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    /** 吊销一个会话；{@code id} 为空时静默忽略（令牌本来就无效，没什么可吊销的）。 */
    public void revoke(Session session) {
        if (session == null || session.id() == null || session.id().isBlank()) {
            return;
        }
        revoked.put(session.id(), session.expiresAt());
        if (revoked.size() > SWEEP_THRESHOLD) {
            sweep(Instant.now());
        }
    }

    public boolean isRevoked(String sessionId) {
        return isRevoked(sessionId, Instant.now());
    }

    /** 显式传入当前时间，便于测试不受真实时钟影响。 */
    public boolean isRevoked(String sessionId, Instant now) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Instant expiresAt = revoked.get(sessionId);
        if (expiresAt == null) {
            return false;
        }
        // 令牌自己都过期了，这条吊销记录就没有存在意义——顺手删掉，别让它变成常驻内存
        if (now != null && !expiresAt.isAfter(now)) {
            revoked.remove(sessionId);
            return false;
        }
        return true;
    }

    /** 清理已过期的吊销记录。 */
    public void sweep(Instant now) {
        int before = revoked.size();
        revoked.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        int removed = before - revoked.size();
        if (removed > 0) {
            log.debug("清理过期吊销记录 {} 条，剩余 {}", removed, revoked.size());
        }
    }

    /** 仅用于测试与观测。 */
    public int size() {
        return revoked.size();
    }
}
