package com.kangheng.healthos.service.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.kangheng.healthos.model.ocr.OcrResult;

/**
 * 把 OCR 行结果拼回自然段落。
 *
 * <p>为什么需要这一步：OCR 服务返回的是"视觉行"，而抽取模型需要的是"语义段落"。
 * 直接把行按顺序用空格连起来会把相邻但无关的两段（比如<b>方名</b>与<b>下一首方的组成</b>）
 * 粘成一句，抽取出来的关系就会挂错主语。这里按纵坐标聚类成段、段内按横坐标排序。
 *
 * <p>纯函数，便于单测；不依赖任何 OCR 厂商。
 */
public final class OcrTextAssembler {

    /** 行高分组的相对阈值：超过行高的 70% 才认为是新段落（同段行距通常小于行高）。 */
    private static final double LINE_GAP_RATIO = 0.7;

    /** 绝对下限，避免"行高极小 + 坐标抖动"把同一行拆成多段。 */
    private static final int MIN_GAP_PX = 6;

    private OcrTextAssembler() {
    }

    public static String assemble(List<OcrResult.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<OcrResult.Line> usable = new ArrayList<>();
        for (OcrResult.Line line : lines) {
            if (line != null && line.text() != null && !line.text().isBlank()) {
                usable.add(line);
            }
        }
        if (usable.isEmpty()) {
            return "";
        }
        usable.sort(Comparator.comparingInt(OcrResult.Line::centerY)
                .thenComparingInt(OcrResult.Line::x1));

        StringBuilder result = new StringBuilder();
        List<OcrResult.Line> group = new ArrayList<>();
        OcrResult.Line previous = null;
        for (OcrResult.Line line : usable) {
            if (previous != null && isNewParagraph(previous, line)) {
                appendGroup(result, group);
                group = new ArrayList<>();
            }
            group.add(line);
            previous = line;
        }
        appendGroup(result, group);
        return result.toString().strip();
    }

    private static boolean isNewParagraph(OcrResult.Line previous, OcrResult.Line current) {
        int gap = current.centerY() - previous.centerY();
        double threshold = Math.max(MIN_GAP_PX,
                Math.max(previous.height(), current.height()) * LINE_GAP_RATIO);
        return gap > threshold;
    }

    private static void appendGroup(StringBuilder result, List<OcrResult.Line> group) {
        if (group.isEmpty()) {
            return;
        }
        List<OcrResult.Line> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparingInt(OcrResult.Line::x1));
        if (!result.isEmpty()) {
            result.append('\n');
        }
        for (OcrResult.Line line : sorted) {
            String text = line.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (needsSpace(result, text)) {
                result.append(' ');
            }
            result.append(text);
        }
    }

    /** 中文与中文之间不加空格；含 ASCII 词时保留空格，避免 "BMI28.4" 这类粘连。 */
    private static boolean needsSpace(StringBuilder builder, String next) {
        if (builder.isEmpty()) {
            return false;
        }
        char last = builder.charAt(builder.length() - 1);
        char first = next.charAt(0);
        if (last == '\n' || first == '\n') {
            return false;
        }
        return !isCjk(last) || !isCjk(first);
    }

    private static boolean isCjk(char ch) {
        return ch >= '\u4e00' && ch <= '\u9fff';
    }
}
