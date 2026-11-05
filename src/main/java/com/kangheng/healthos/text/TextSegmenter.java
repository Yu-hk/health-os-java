package com.kangheng.healthos.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.model.classics.Segment;

/**
 * 文本分段与二分。
 *
 * <p>对应原项目 {@code pipeline.py} 的 {@code _split_text} 与 {@code _bisect_segment}：
 * 先按固定窗口切分并保留重叠，再尽量在句读处断开；抽取失败且属于"体量敏感"时，
 * 把分段对半切开重跑，而不是原样重试。
 */
public final class TextSegmenter {

    private static final char[] BOUNDARIES = {'。', '！', '？', '；', '\n', '.', '!', '?'};

    private TextSegmenter() {
    }

    public static List<Segment> segment(String text, int chunkChars, int overlap) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return segments;
        }
        if (chunkChars <= 0) {
            throw new IllegalArgumentException("chunkChars 必须为正数");
        }
        if (overlap < 0 || overlap >= chunkChars) {
            throw new IllegalArgumentException("overlap 必须满足 0 <= overlap < chunkChars");
        }
        int length = text.length();
        int start = 0;
        int index = 0;
        while (start < length) {
            int hardEnd = Math.min(start + chunkChars, length);
            int end = hardEnd;
            if (hardEnd < length) {
                int window = Math.max(1, chunkChars / 6);
                int from = Math.max(start + 1, hardEnd - window);
                int boundary = lastBoundary(text, from, hardEnd);
                if (boundary > from) {
                    end = boundary;
                }
            }
            String content = text.substring(start, end);
            // 防御性收敛：句读回退不能把分段切到比上一段更早的位置
            if (!segments.isEmpty() && end <= segments.get(segments.size() - 1).charEnd()) {
                end = hardEnd;
                content = text.substring(start, end);
            }
            segments.add(new Segment(index++, start, end, content, Ids.sha256Hex(content)));
            if (end >= length) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return segments;
    }

    /**
     * 把分段对半切开。返回空表示无法再切（太短），调用方应放弃二分并按失败处理。
     */
    public static Optional<List<Segment>> bisect(Segment segment, int minChars) {
        if (segment == null || segment.length() < Math.max(minChars, 2) * 2) {
            return Optional.empty();
        }
        int mid = segment.charStart() + segment.length() / 2;
        int lower = segment.charStart() + minChars;
        int upper = segment.charEnd() - minChars;
        if (lower >= upper) {
            return Optional.empty();
        }
        int cut = nearestBoundary(segment, mid, lower, upper);
        if (cut <= segment.charStart() || cut >= segment.charEnd()) {
            return Optional.empty();
        }
        Segment left = slice(segment, segment.charStart(), cut);
        Segment right = slice(segment, cut, segment.charEnd());
        return Optional.of(List.of(left, right));
    }

    private static Segment slice(Segment source, int from, int to) {
        String content = source.text().substring(from - source.charStart(), to - source.charStart());
        return new Segment(source.index(), from, to, content, Ids.sha256Hex(content));
    }

    private static int nearestBoundary(Segment segment, int mid, int lower, int upper) {
        String text = segment.text();
        int midLocal = mid - segment.charStart();
        for (int offset = 0; offset <= Math.min(midLocal, text.length() - midLocal); offset++) {
            int forward = midLocal + offset;
            if (forward < text.length() && isBoundary(text.charAt(forward))) {
                int absolute = segment.charStart() + forward + 1;
                if (absolute >= lower && absolute <= upper) {
                    return absolute;
                }
            }
            int backward = midLocal - offset;
            if (backward > 0 && isBoundary(text.charAt(backward))) {
                int absolute = segment.charStart() + backward + 1;
                if (absolute >= lower && absolute <= upper) {
                    return absolute;
                }
            }
        }
        return mid;
    }

    private static int lastBoundary(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (isBoundary(text.charAt(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean isBoundary(char ch) {
        for (char boundary : BOUNDARIES) {
            if (ch == boundary) {
                return true;
            }
        }
        return false;
    }
}
