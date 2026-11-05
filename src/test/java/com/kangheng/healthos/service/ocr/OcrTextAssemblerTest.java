package com.kangheng.healthos.service.ocr;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.kangheng.healthos.model.ocr.OcrResult;

import static org.assertj.core.api.Assertions.assertThat;

class OcrTextAssemblerTest {

    private static OcrResult.Line line(String text, int x1, int y1, int x2, int y2) {
        return new OcrResult.Line(text, 0.95, x1, y1, x2, y2);
    }

    @Test
    @DisplayName("同一视觉行内的碎片按横坐标拼接，中文之间不加空格")
    void joinsInlineFragmentsByX() {
        // 故意打乱输入顺序：OCR 服务返回的词序不保证
        String text = OcrTextAssembler.assemble(List.of(
                line("主治脾胃气虚", 300, 200, 520, 240),
                line("四君子汤", 100, 200, 280, 240)));
        assertThat(text).isEqualTo("四君子汤主治脾胃气虚");
    }

    @Test
    @DisplayName("行距明显变大时切成两段，避免不同条目粘成一句")
    void splitsWhenLineGapGrows() {
        String text = OcrTextAssembler.assemble(List.of(
                line("四君子汤：人参、白术、茯苓、甘草。", 100, 100, 700, 140),
                line("六味地黄丸：熟地黄、山茱萸、山药。", 100, 300, 760, 340)));
        assertThat(text).isEqualTo(
                "四君子汤：人参、白术、茯苓、甘草。\n六味地黄丸：熟地黄、山茱萸、山药。");
    }

    @Test
    @DisplayName("含 ASCII 的词之间保留空格，避免数字与单位粘连")
    void keepsSpaceForAsciiTokens() {
        String text = OcrTextAssembler.assemble(List.of(
                line("BMI", 100, 100, 160, 140),
                line("28.4", 170, 100, 240, 140),
                line("kg/m2", 250, 100, 340, 140)));
        assertThat(text).isEqualTo("BMI 28.4 kg/m2");
    }

    @Test
    @DisplayName("空输入与全空白行返回空串，不返回 null")
    void handlesEmptyInput() {
        assertThat(OcrTextAssembler.assemble(null)).isEmpty();
        assertThat(OcrTextAssembler.assemble(List.of())).isEmpty();
        assertThat(OcrTextAssembler.assemble(List.of(line("   ", 0, 0, 10, 10)))).isEmpty();
    }

    @Test
    @DisplayName("置信度低的识别结果由上游过滤，这里不擅自丢弃文本")
    void doesNotFilterByConfidence() {
        OcrResult.Line low = new OcrResult.Line("模糊字", 0.1, 100, 100, 200, 140);
        assertThat(OcrTextAssembler.assemble(List.of(low))).isEqualTo("模糊字");
    }

    @Test
    @DisplayName("同一行内 y 坐标轻微抖动仍视为同一行")
    void toleratesVerticalJitter() {
        String text = OcrTextAssembler.assemble(List.of(
                line("脾胃", 100, 200, 200, 240),
                line("气虚", 210, 204, 310, 244)));
        assertThat(text).isEqualTo("脾胃气虚");
    }
}
