package com.kangheng.healthos.service.care;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.care.CareContract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF 导出的真实渲染。
 *
 * <p>这组用例不 mock 排版：用本机真实中文字体跑一遍，再把文字从 PDF 里抽回来比对。
 * 理由是"能生成一个 %PDF 开头的文件"根本不算验证——字体没嵌入时 PDFBox 会抛异常，
 * 但换行、分页、中文丢字这些问题只有抽文才能发现。
 *
 * <p>没有中文字体的环境（CI）会被 {@link Assumptions} 跳过，而不是失败：
 * 这是环境缺件，不是代码缺陷；缺件时的行为由 {@link #无字体时必须明确报不可用()} 覆盖。
 */
class CarePdfRendererTest {

    private static final String WINDOWS_FONT = "C:/Windows/Fonts/msyh.ttc";

    private CarePdfRenderer rendererWithFont() {
        HealthOsProperties properties = new HealthOsProperties();
        properties.getCare().getPdf().setFontPath(WINDOWS_FONT);
        return new CarePdfRenderer(properties);
    }

    @Test
    @DisplayName("真实字体渲染：中文不丢字，内容可被抽取回来")
    void 渲染结果可以抽出中文原文() throws Exception {
        Assumptions.assumeTrue(new File(WINDOWS_FONT).isFile(), "本机无中文字体，跳过渲染验证");

        CarePdfRenderer renderer = rendererWithFont();
        assertThat(renderer.available()).isTrue();
        assertThat(renderer.status()).containsEntry("available", true);

        byte[] pdf = renderer.render(view(), Map.of("basicInfo", Map.of("name", "张三")));
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("多学科健康评估报告");
            assertThat(text).contains("张三");
            assertThat(text).contains("综合健康画像");
            // 正文与体检依据都要在，否则导出的就是一份"只有标题"的空壳
            assertThat(text).contains("血压偏高，建议减少钠摄入。");
            assertThat(text).contains("本节引用指标：SBP、DBP");
            // 签发信息与免责声明缺一不可
            assertThat(text).contains("已签发");
            assertThat(text).contains("确认人：doctor");
            assertThat(text).contains("不构成独立诊断或处方");
            assertThat(text).contains("第 1 页 / 共");
        }
    }

    @Test
    @DisplayName("草稿导出必须带草稿标记")
    void 草稿在页眉留痕() throws Exception {
        Assumptions.assumeTrue(new File(WINDOWS_FONT).isFile(), "本机无中文字体，跳过渲染验证");

        CareContract.DocumentView draft = new CareContract.DocumentView(
                "cdoc_1", "alice", "report", "pat_1", "DRAFT", 1, null, null,
                "exam_1", 2, null, null, null, null, true, sections(),
                List.of(), null, null, CareContract.DISCLAIMER,
                OffsetDateTime.now(), OffsetDateTime.now());

        byte[] pdf = rendererWithFont().render(draft, Map.of());
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("草稿，未签发 / 未生效");
        }
    }

    @Test
    @DisplayName("无字体时必须明确报不可用，不能出无字 PDF")
    void 无字体时必须明确报不可用() {
        HealthOsProperties properties = new HealthOsProperties();
        properties.getCare().getPdf().setEnabled(false);

        CarePdfRenderer renderer = new CarePdfRenderer(properties);
        assertThat(renderer.available()).isFalse();
        assertThat(renderer.status()).containsEntry("available", false);
        assertThat(renderer.status().get("reason")).asString().isNotBlank();

        assertThatThrownBy(() -> renderer.render(view(), Map.of()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getCode()).isEqualTo(CarePdfRenderer.ERROR_UNAVAILABLE);
                    assertThat(biz.getStatus().value()).isEqualTo(503);
                });
    }

    @Test
    @DisplayName("超长正文会自动分页，不会压到页脚")
    void 长文本自动分页() throws Exception {
        Assumptions.assumeTrue(new File(WINDOWS_FONT).isFile(), "本机无中文字体，跳过渲染验证");

        String longText = "本段用于验证分页行为。".repeat(300);
        List<CareContract.SectionView> sections = List.of(
                new CareContract.SectionView("profile", "综合健康画像", longText,
                        List.of("SBP"), true));

        CareContract.DocumentView view = new CareContract.DocumentView(
                "cdoc_2", "alice", "report", "pat_1", "SIGNED", 1, 1, null,
                "exam_1", 2, null, null, null, null, true, sections,
                List.of(), null, null, CareContract.DISCLAIMER,
                OffsetDateTime.now(), OffsetDateTime.now());

        byte[] pdf = rendererWithFont().render(view, Map.of());
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("共 " + document.getNumberOfPages() + " 页");
        }
    }

    private CareContract.DocumentView view() {
        CareContract.SignatureView signature = new CareContract.SignatureView(
                "doctor", "内容与体检依据一致，同意签发。", 1, "sha256:abc", "2026-11-01",
                "report", OffsetDateTime.now());
        return new CareContract.DocumentView(
                "cdoc_1", "alice", "report", "pat_1", "SIGNED", 1, 1, 1,
                "exam_1", 2, null, null, null, null, true, sections(),
                List.of(), null, signature, CareContract.DISCLAIMER,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private List<CareContract.SectionView> sections() {
        return List.of(
                new CareContract.SectionView("profile", "综合健康画像",
                        "42 岁男性，BMI 偏高，血压偏高，建议减少钠摄入。",
                        List.of("SBP", "DBP"), true),
                new CareContract.SectionView("modern_assessment", "现代医学评估",
                        "血压偏高，建议减少钠摄入。", List.of(), true));
    }
}
