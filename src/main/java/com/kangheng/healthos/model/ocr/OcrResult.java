package com.kangheng.healthos.model.ocr;

import java.util.List;

/**
 * 单页 OCR 结果。
 *
 * <p>只保留"行文本 + 置信度 + 包围盒"三样东西：抽取与引用回溯都不需要字符级坐标，
 * 保留过多结构会让下游协议耦合到某一家 OCR 厂商。
 */
public record OcrResult(String engine, List<Line> lines) {

    /**
     * 一行识别结果。{@code box} 为 [x1, y1, x2, y2] 的页面像素坐标。
     */
    public record Line(String text, double confidence, int x1, int y1, int x2, int y2) {

        public Line {
            if (text == null) {
                text = "";
            }
        }

        public int centerY() {
            return (y1 + y2) / 2;
        }

        public int height() {
            return Math.max(1, y2 - y1);
        }
    }

    public static OcrResult empty(String engine) {
        return new OcrResult(engine, List.of());
    }

    public boolean isEmpty() {
        return lines == null || lines.isEmpty();
    }
}
