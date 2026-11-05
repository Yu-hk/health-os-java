package com.kangheng.healthos.text;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceLocatorTest {

    private static final String MARKDOWN = "序言\n"
            + "人参味甘，微寒。\n"
            + "\f\n"
            + "黄芪补气升阳。\n"
            + "\f\n"
            + "甘草调和诸药。\n";

    @Test
    @DisplayName("精确匹配返回真实字符区间")
    void locatesExactQuote() {
        Optional<EvidenceLocator.Located> located = EvidenceLocator.locate(MARKDOWN, "黄芪补气升阳。");
        assertThat(located).isPresent();
        EvidenceLocator.Located hit = located.get();
        assertThat(hit.quote()).isEqualTo("黄芪补气升阳。");
        assertThat(MARKDOWN.substring(hit.charStart(), hit.charEnd())).isEqualTo(hit.quote());
    }

    @Test
    @DisplayName("按换页符还原页码")
    void resolvesPageNumbers() {
        assertThat(EvidenceLocator.locate(MARKDOWN, "人参味甘，微寒。").orElseThrow().page()).isEqualTo(1);
        assertThat(EvidenceLocator.locate(MARKDOWN, "黄芪补气升阳。").orElseThrow().page()).isEqualTo(2);
        assertThat(EvidenceLocator.locate(MARKDOWN, "甘草调和诸药。").orElseThrow().page()).isEqualTo(3);
    }

    @Test
    @DisplayName("模型改动空白后仍能定位")
    void toleratesWhitespaceDifferences() {
        String markdown = "人参 味甘，微寒。\n黄芪  补气。";
        Optional<EvidenceLocator.Located> located = EvidenceLocator.locate(markdown, "人参味甘，微寒。");
        assertThat(located).isPresent();
        assertThat(located.get().charStart()).isZero();
        assertThat(located.get().charEnd()).isEqualTo("人参 味甘，微寒。".length());
    }

    @Test
    @DisplayName("引用被截断时退化为前缀匹配")
    void fallsBackToPrefix() {
        String quote = "人参味甘，微寒，归脾肺经，为补气要药，久服轻身延年。";
        Optional<EvidenceLocator.Located> located = EvidenceLocator.locate(MARKDOWN, quote);
        assertThat(located).isEmpty();

        String markdown = quote + "\n其他内容";
        Optional<EvidenceLocator.Located> prefixHit =
                EvidenceLocator.locate(markdown, quote.substring(0, 15) + "（模型续写）");
        assertThat(prefixHit).isPresent();
        assertThat(markdown.startsWith(prefixHit.get().quote())).isTrue();
    }

    @Test
    @DisplayName("找不到或输入为空时返回空，不编造位置")
    void returnsEmptyWhenNotFound() {
        assertThat(EvidenceLocator.locate(MARKDOWN, "完全不存在的句子。")).isEmpty();
        assertThat(EvidenceLocator.locate(MARKDOWN, "")).isEmpty();
        assertThat(EvidenceLocator.locate("", "人参")).isEmpty();
        assertThat(EvidenceLocator.locate(null, "人参")).isEmpty();
    }

    @Test
    @DisplayName("偏移在首字符时页码为 1")
    void pageAtBoundary() {
        assertThat(EvidenceLocator.pageAt(MARKDOWN, 0)).isEqualTo(1);
        assertThat(EvidenceLocator.pageAt(null, 5)).isEqualTo(1);
    }
}
