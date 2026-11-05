package com.kangheng.healthos.provider.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.spi.agent.McpToolGrants;

/**
 * 内存版凭证登记处。
 *
 * <p>三条实现约束：
 * <ul>
 *   <li><b>随机 ID</b>：凭证不编码 owner/patientId（不用 base64、不做签名载荷），
 *       否则它本身就是一份可被读取的身份信息，落进日志就等着出事；</li>
 *   <li><b>有过期</b>：凭证只描述"这次操作"，TTL 一到就作废。进程崩了也不用清理，
 *       因为整张表在内存里；</li>
 *   <li><b>用后可选废弃</b>：{@link #resolve} 只读，不自动删除——一次 {@code tools/call}
 *       可能因为协议重试被问两遍，删早了会把正常调用判成 403。</li>
 * </ul>
 *
 * <p>顺带做一次惰性清理：每次签发票时把过期项摘掉。表最多只有"并发操作数"条，
 * 不需要定时线程。
 */
public class InMemoryMcpToolGrants implements McpToolGrants {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    public InMemoryMcpToolGrants(Duration ttl) {
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(60) : ttl;
    }

    @Override
    public String issue(String owner, String patientId, Set<String> scopeWires) {
        purgeExpired();
        String id = Ids.newId("mgrant");
        Set<String> scopes = scopeWires == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(scopeWires));
        entries.put(id, new Entry(new Grant(owner, patientId, scopes), Instant.now().plus(ttl)));
        return id;
    }

    @Override
    public Grant resolve(String grantId) {
        if (grantId == null || grantId.isBlank()) {
            return null;
        }
        Entry entry = entries.get(grantId);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            entries.remove(grantId);
            return null;
        }
        return entry.grant();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(item -> item.getValue().expiresAt().isBefore(now));
    }

    private record Entry(Grant grant, Instant expiresAt) {
    }
}
