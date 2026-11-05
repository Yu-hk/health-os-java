package com.kangheng.healthos.service.auth;

import java.time.Duration;
import java.time.Instant;

import com.kangheng.healthos.model.auth.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRevocationRegistryTest {

    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

    @Test
    @DisplayName("吊销后立即判定为已吊销")
    void revokeTakesEffect() {
        SessionRevocationRegistry registry = new SessionRevocationRegistry();
        Session session = session("jti-1", NOW.plus(Duration.ofHours(1)));
        assertThat(registry.isRevoked("jti-1", NOW)).isFalse();
        registry.revoke(session);
        assertThat(registry.isRevoked("jti-1", NOW)).isTrue();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("令牌自然过期后吊销记录自动失效并被清掉")
    void expiredRecordIsPurged() {
        SessionRevocationRegistry registry = new SessionRevocationRegistry();
        registry.revoke(session("jti-1", NOW.plus(Duration.ofMinutes(1))));
        assertThat(registry.isRevoked("jti-1", NOW.plus(Duration.ofMinutes(2)))).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("空会话或空 jti 静默忽略，不写入垃圾记录")
    void ignoresBlankId() {
        SessionRevocationRegistry registry = new SessionRevocationRegistry();
        registry.revoke(null);
        registry.revoke(session("", NOW.plus(Duration.ofHours(1))));
        registry.revoke(session(null, NOW.plus(Duration.ofHours(1))));
        assertThat(registry.size()).isZero();
        assertThat(registry.isRevoked(null, NOW)).isFalse();
    }

    @Test
    @DisplayName("只吊销指定的那个 jti，不影响同一用户的其它会话")
    void revokesOnlyTargetSession() {
        SessionRevocationRegistry registry = new SessionRevocationRegistry();
        registry.revoke(session("jti-1", NOW.plus(Duration.ofHours(1))));
        assertThat(registry.isRevoked("jti-2", NOW)).isFalse();
    }

    @Test
    @DisplayName("sweep 只清理过期记录")
    void sweepKeepsLiveRecords() {
        SessionRevocationRegistry registry = new SessionRevocationRegistry();
        registry.revoke(session("alive", NOW.plus(Duration.ofHours(2))));
        registry.revoke(session("dead", NOW.plus(Duration.ofMinutes(1))));
        registry.sweep(NOW.plus(Duration.ofMinutes(5)));
        assertThat(registry.isRevoked("alive", NOW.plus(Duration.ofMinutes(5)))).isTrue();
        assertThat(registry.size()).isEqualTo(1);
    }

    private static Session session(String id, Instant expiresAt) {
        return new Session("alice", id, NOW, expiresAt);
    }
}
