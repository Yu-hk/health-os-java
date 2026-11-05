package com.kangheng.healthos.provider.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 纯单元层：维度解析与实例姿态不需要连库，是"配置撞上真实列定义"前的唯一确认点。
 * 真正建索引、装扩展的验证在真数据库上手动跑（见 README §3.21 与 smoke 脚本）。
 */
class PgVectorIndexTest {

    @Test
    @DisplayName("解析 vector(1024) 取出 1024")
    void parsesFixedDimension() {
        assertThat(PgVectorIndex.declaredDimension("vector(1024)")).isEqualTo(1024);
    }

    @Test
    @DisplayName("不同维度也能解析")
    void parsesOtherDimension() {
        assertThat(PgVectorIndex.declaredDimension("vector(768)")).isEqualTo(768);
    }

    @Test
    @DisplayName("不定长 vector 没有维度，返回 null")
    void undimensionedReturnsNull() {
        assertThat(PgVectorIndex.declaredDimension("vector")).isNull();
    }

    @Test
    @DisplayName("null 或畸形串返回 null，不抛")
    void malformedReturnsNull() {
        assertThat(PgVectorIndex.declaredDimension(null)).isNull();
        assertThat(PgVectorIndex.declaredDimension("text")).isNull();
        assertThat(PgVectorIndex.declaredDimension("vector(abc)")).isNull();
    }

    @Test
    @DisplayName("disabled() 永远不可用且姿态明确")
    void disabledInstance() {
        PgVectorIndex index = PgVectorIndex.disabled();
        assertThat(index.available()).isFalse();
        assertThat(index.posture()).isEqualTo("disabled:test");
    }
}
