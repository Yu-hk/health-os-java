package com.kangheng.healthos.text;

import com.kangheng.healthos.model.classics.Segment;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextSegmenterTest {

    private static String buildText(int sentences) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sentences; i++) {
            builder.append("第").append(i).append("条：四君子汤主治脾胃气虚，人参与白术同用，久服可补中益气。");
        }
        return builder.toString();
    }

    @Test
    @DisplayName("分段正文与原始区间严格一致")
    void segmentsMatchOriginalSlices() {
        String text = buildText(40);
        List<Segment> segments = TextSegmenter.segment(text, 300, 30);
        assertThat(segments).isNotEmpty();
        for (Segment segment : segments) {
            assertThat(segment.text()).isEqualTo(text.substring(segment.charStart(), segment.charEnd()));
            assertThat(segment.sha256()).hasSize(64);
        }
    }

    @Test
    @DisplayName("分段覆盖全文且不超过窗口上限")
    void segmentsCoverWholeText() {
        String text = buildText(40);
        List<Segment> segments = TextSegmenter.segment(text, 300, 30);
        assertThat(segments.get(0).charStart()).isZero();
        assertThat(segments.get(segments.size() - 1).charEnd()).isEqualTo(text.length());
        for (Segment segment : segments) {
            assertThat(segment.length()).isLessThanOrEqualTo(300);
        }
        for (int i = 1; i < segments.size(); i++) {
            assertThat(segments.get(i).charStart()).isGreaterThanOrEqualTo(segments.get(i - 1).charStart());
            assertThat(segments.get(i).charEnd()).isGreaterThan(segments.get(i - 1).charEnd());
        }
    }

    @Test
    @DisplayName("句读密集时优先在句读处断开")
    void prefersSentenceBoundary() {
        String text = "甲。".repeat(120);
        List<Segment> segments = TextSegmenter.segment(text, 120, 10);
        assertThat(segments.size()).isGreaterThan(1);
        for (int i = 0; i < segments.size() - 1; i++) {
            String lastChar = segments.get(i).text().substring(segments.get(i).text().length() - 1);
            assertThat(lastChar).isIn("。", "\n");
        }
    }

    @Test
    @DisplayName("短文本不切分；空文本返回空列表")
    void handlesShortAndEmptyText() {
        assertThat(TextSegmenter.segment("人参味甘。", 1500, 150)).hasSize(1);
        assertThat(TextSegmenter.segment("", 1500, 150)).isEmpty();
        assertThat(TextSegmenter.segment(null, 1500, 150)).isEmpty();
    }

    @Test
    @DisplayName("非法参数显式失败，不静默降级")
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> TextSegmenter.segment("abc", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TextSegmenter.segment("abc", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TextSegmenter.segment("abc", 100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
