package com.kangheng.healthos.provider.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import com.kangheng.healthos.spi.agent.McpToolGrants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 凭证登记处：随机、短命、可还原身份。
 *
 * <p>最要紧的一条是"凭证本身不含身份"——它落进日志、被某个子进程打印出来，
 * 都不该等价于泄露"哪个账号读哪个患者"。所以这里的断言直接检查 ID 里
 * 既没有 owner 也没有 patientId。
 */
class InMemoryMcpToolGrantsTest {

    @Test
    @DisplayName("签发后能还原身份与范围，且凭证 ID 里不含身份信息")
    void issuedGrantCarriesIdentityButIdDoesNot() {
        InMemoryMcpToolGrants grants = new InMemoryMcpToolGrants(Duration.ofMinutes(1));
        String id = grants.issue("alice@example.com", "pat_123", Set.of("memory", "report"));

        assertThat(id).startsWith("mgrant_");
        assertThat(id).doesNotContain("alice", "pat_123", "memory", "example");

        McpToolGrants.Grant grant = grants.resolve(id);
        assertThat(grant).isNotNull();
        assertThat(grant.owner()).isEqualTo("alice@example.com");
        assertThat(grant.patientId()).isEqualTo("pat_123");
        assertThat(grant.scopeWires()).containsExactlyInAnyOrder("memory", "report");
    }

    @Test
    @DisplayName("两次签发互不相同：同一账号同一患者也不复用")
    void grantIdsAreUnique() {
        InMemoryMcpToolGrants grants = new InMemoryMcpToolGrants(Duration.ofMinutes(1));
        String first = grants.issue("admin", "pat_1", Set.of("memory"));
        String second = grants.issue("admin", "pat_1", Set.of("memory"));

        assertThat(first).isNotEqualTo(second);
        assertThat(grants.resolve(first)).isNotNull();
        assertThat(grants.resolve(second)).isNotNull();
    }

    @Test
    @DisplayName("过期后查不到；未知 ID、null、空串一律返回 null")
    void expiredOrUnknownGrantResolvesToNull() throws InterruptedException {
        InMemoryMcpToolGrants grants = new InMemoryMcpToolGrants(Duration.ofMillis(30));
        String id = grants.issue("admin", "pat_1", Set.of("memory"));
        Thread.sleep(60);

        assertThat(grants.resolve(id)).isNull();
        assertThat(grants.resolve("mgrant_nope")).isNull();
        assertThat(grants.resolve(null)).isNull();
        assertThat(grants.resolve("  ")).isNull();
    }

    @Test
    @DisplayName("范围集合做防御性拷贝：签发方事后改集合不影响已签发的凭证")
    void scopeSetIsCopied() {
        InMemoryMcpToolGrants grants = new InMemoryMcpToolGrants(Duration.ofMinutes(1));
        Set<String> mutable = new LinkedHashSet<>(Set.of("memory"));
        String id = grants.issue("admin", "pat_1", mutable);
        mutable.add("statistics");

        assertThat(grants.resolve(id).scopeWires()).containsExactly("memory");
    }

    @Test
    @DisplayName("TTL 为 0 或负数时回落到 60 秒，而不是签发即过期")
    void invalidTtlFallsBack() {
        InMemoryMcpToolGrants grants = new InMemoryMcpToolGrants(Duration.ZERO);
        String id = grants.issue("admin", "pat_1", Set.of("memory"));

        assertThat(grants.resolve(id)).isNotNull();
    }
}
