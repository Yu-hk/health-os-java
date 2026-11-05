package com.kangheng.healthos.text;

import java.util.List;
import java.util.Optional;

import com.kangheng.healthos.common.Ids;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.kangheng.healthos.model.classics.Segment;
import com.kangheng.healthos.text.TextSegmenter;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentBisectTest {

    private static Segment segmentOf(String text) {
        return new Segment(0, 0, text.length(), text, Ids.sha256Hex(text));
    }

    @Test
    @DisplayName("二分出的两段区间连续、拼接后等于原分段")
    void bisectSplitsContiguously() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            builder.append("第").append(i).append("条：人参味甘微寒，归脾肺经，可补气。");
        }
        String text = builder.toString();
        Segment segment = segmentOf(text);

        Optional<List<Segment>> halves = TextSegmenter.bisect(segment, 100);
        assertThat(halves).isPresent();
        List<Segment> parts = halves.get();
        assertThat(parts).hasSize(2);

        Segment left = parts.get(0);
        Segment right = parts.get(1);
        assertThat(left.charStart()).isZero();
        assertThat(left.charEnd()).isEqualTo(right.charStart());
        assertThat(right.charEnd()).isEqualTo(text.length());
        assertThat(left.text() + right.text()).isEqualTo(text);
        assertThat(left.sha256()).hasSize(64);
        assertThat(right.sha256()).hasSize(64);
    }

    @Test
    @DisplayName("二分点落在句读处")
    void bisectPrefersSentenceBoundary() {
        String text = "甲。".repeat(60);
        Optional<List<Segment>> halves = TextSegmenter.bisect(segmentOf(text), 20);
        assertThat(halves).isPresent();
        Segment left = halves.get().get(0);
        assertThat(left.text()).endsWith("。");
    }

    @Test
    @DisplayName("过短分段不再二分")
    void refusesToSplitTooSmallSegment() {
        assertThat(TextSegmenter.bisect(segmentOf("人参味甘。"), 400)).isEmpty();
        assertThat(TextSegmenter.bisect(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("检查点键只依赖区间与内容，重跑可命中")
    void checkpointKeyIsStable() {
        Segment segment = segmentOf("人参味甘微寒");
        assertThat(segment.checkpointKey()).isEqualTo(segmentOf("人参味甘微寒").checkpointKey());
        assertThat(segment.checkpointKey()).startsWith("0-6-");
    }
}
