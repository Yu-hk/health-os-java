package com.kangheng.healthos.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    @DisplayName("随机 ID 带前缀且互不相同")
    void newIdIsPrefixedAndUnique() {
        String first = Ids.newId("doc");
        String second = Ids.newId("doc");
        assertThat(first).startsWith("doc_");
        assertThat(first).isNotEqualTo(second);
        assertThat(first.length()).isEqualTo(4 + 24);
    }

    @Test
    @DisplayName("稳定 ID 对相同输入可重放，对任意差异敏感")
    void stableIdIsDeterministic() {
        String a = Ids.stableId("ev", "doc_1", "42", "人参味甘");
        String b = Ids.stableId("ev", "doc_1", "42", "人参味甘");
        String c = Ids.stableId("ev", "doc_1", "43", "人参味甘");
        assertThat(a).isEqualTo(b).startsWith("ev_");
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("分段边界不会被拼接歧义撞出相同 ID")
    void stableIdAvoidsConcatenationCollision() {
        assertThat(Ids.stableId("x", "ab", "c")).isNotEqualTo(Ids.stableId("x", "a", "bc"));
    }

    @Test
    @DisplayName("SHA-256 与标准参考值一致")
    void sha256MatchesKnownVector() {
        assertThat(Ids.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
