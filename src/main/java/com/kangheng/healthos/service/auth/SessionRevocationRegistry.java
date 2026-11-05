package com.kangheng.healthos.service.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.auth.AuthSessionRevocationMapper;
import com.kangheng.healthos.model.auth.AuthSessionRevocation;
import com.kangheng.healthos.model.auth.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 会话吊销名单：登出即失效。
 *
 * <p>无状态令牌的代价是"发出去就收不回"。这里记一份 {@code jti -> 原到期时间} 的记录，
 * 请求进来时比对一次，命中就当作未登录。
 *
 * <p><b>为什么落库而不是留在内存</b>：留在内存有两个硬伤，且都不是"单机可接受的取舍"：
 * <ol>
 *   <li>重启即清空——登出过的令牌在服务重启后又活了过来，这是安全问题，不是可用性问题；</li>
 *   <li>多实例各存一份——在 A 登出后拿同一张 Cookie 打 B 照样进得去，等于没有登出。</li>
 * </ol>
 * 表的读写都走主键（单次约 0.1ms），为这点开销换掉一个安全缺口是划算的。
 *
 * <p><b>内存那份仍然保留，但角色变了</b>：从"权威存储"降为"库不可用时的兜底"。
 * 一旦走到兜底会打一次 warn 并在 {@link #degraded()} 里留下痕迹——
 * 降级本身可以接受，静默降级不行：那会让人以为登出一直是可靠的。
 * 注意兜底只保证本实例内仍拦得住，重启或换节点就会漏，所以它是应急而不是常态。
 *
 * <p>容量不会无限增长：每条记录都带原到期时间，过期即失效，超过阈值时顺带清理一轮。
 */
@Component
public class SessionRevocationRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationRegistry.class);

    /** 超过这个条数就顺手清一轮过期记录，避免只在写路径上堆积。 */
    private static final int SWEEP_THRESHOLD = 256;

    /** 本地名单：库可用时是缓存兼降级兜底，库不可用时是唯一依仗。 */
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    /** {@code null} 表示纯内存模式（{@code auth.session-store=memory} 或单元测试）。 */
    private final AuthSessionRevocationMapper mapper;

    /** 是否发生过降级。只在首次降级时打日志，避免每个请求刷一行。 */
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    /** 纯内存模式：单元测试与 {@code session-store=memory} 用。 */
    public SessionRevocationRegistry() {
        this.mapper = null;
    }

    @Autowired
    public SessionRevocationRegistry(AuthSessionRevocationMapper mapper, HealthOsProperties properties) {
        boolean jdbc = !"memory".equalsIgnoreCase(properties.getAuth().getSessionStore());
        this.mapper = jdbc ? mapper : null;
        if (!jdbc) {
            log.warn("会话吊销名单使用内存模式（auth.session-store=memory）：重启即清空、多实例不共享，"
                    + "仅限单实例本地调试");
        }
    }

    /** 吊销一个会话；{@code id} 为空时静默忽略（令牌本来就无效，没什么可吊销的）。 */
    public void revoke(Session session) {
        if (session == null || session.id() == null || session.id().isBlank()) {
            return;
        }
        // 先写本地：库写失败时这一份还能在本实例内拦住
        revoked.put(session.id(), session.expiresAt());
        if (mapper != null) {
            AuthSessionRevocation row = new AuthSessionRevocation();
            row.setSessionId(session.id());
            row.setSubject(session.subject());
            row.setExpiresAt(offset(session.expiresAt()));
            row.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException already) {
                // 重复登出是常态（前端重试、用户连点），幂等处理，不打日志
            } catch (Exception ex) {
                degrade("吊销记录写入失败，已降级为仅内存（重启后这条吊销会丢）", ex);
            }
        }
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
        if (mapper == null) {
            return isRevokedLocally(sessionId, now);
        }
        try {
            AuthSessionRevocation row = mapper.selectById(sessionId);
            if (row == null) {
                return false;
            }
            Instant expiresAt = row.getExpiresAt() == null ? null : row.getExpiresAt().toInstant();
            // 令牌自己都过期了，这条记录就没有存在意义（清理交给 sweep，这里只判"未吊销"）
            if (expiresAt == null || (now != null && !expiresAt.isAfter(now))) {
                return false;
            }
            return true;
        } catch (Exception ex) {
            degrade("会话吊销名单查询失败，已降级为本地名单（跨节点可能漏判）", ex);
            return isRevokedLocally(sessionId, now);
        }
    }

    /** 本地名单判定；命中过期记录时顺手删掉，别让它变成常驻内存。 */
    private boolean isRevokedLocally(String sessionId, Instant now) {
        Instant expiresAt = revoked.get(sessionId);
        if (expiresAt == null) {
            return false;
        }
        if (now != null && !expiresAt.isAfter(now)) {
            revoked.remove(sessionId);
            return false;
        }
        return true;
    }

    /** 清理已过期的吊销记录（本地与库各清一次，两边都可能各自积压）。 */
    public void sweep(Instant now) {
        int before = revoked.size();
        revoked.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        int removed = before - revoked.size();
        if (removed > 0) {
            log.debug("清理过期吊销记录 {} 条，剩余 {}", removed, revoked.size());
        }
        if (mapper != null) {
            try {
                int rows = mapper.deleteExpired(offset(now));
                if (rows > 0) {
                    log.debug("清理库内过期吊销记录 {} 条", rows);
                }
            } catch (Exception ex) {
                degrade("库内过期吊销记录清理失败（不影响判定，下次再清）", ex);
            }
        }
    }

    /**
     * 仅用于测试与观测：当前本地名单条数。
     *
     * <p>库模式下这个数字**不代表**吊销总量——它只是本地那份缓存/兜底。
     * 要看总量用 {@link #count()}。
     */
    public int size() {
        return revoked.size();
    }

    /** 吊销记录总数。库模式查库，内存模式等同 {@link #size()}。 */
    public int count() {
        if (mapper == null) {
            return revoked.size();
        }
        try {
            Long total = mapper.selectCount(null);
            return total == null ? 0 : total.intValue();
        } catch (Exception ex) {
            degrade("吊销记录计数失败，退回本地条数", ex);
            return revoked.size();
        }
    }

    /** 是否发生过降级：健康检查与排查用。一旦为真就不会自己回到 false。 */
    public boolean degraded() {
        return degraded.get();
    }

    /** 是否使用库存储（false = 纯内存模式）。 */
    public boolean shared() {
        return mapper != null;
    }

    /** 降级只记第一次，避免库长时间不可用时把日志刷满。 */
    private void degrade(String message, Exception ex) {
        if (degraded.compareAndSet(false, true)) {
            log.warn("{}: {}", message, ex.getMessage());
        } else {
            log.debug("{}: {}", message, ex.getMessage());
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
