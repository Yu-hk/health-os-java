package com.kangheng.healthos.service.classics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.provider.tracing.DirectTraceWriter;
import com.kangheng.healthos.service.tracing.TracingService;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.provider.ocr.DisabledOcrProvider;
import com.kangheng.healthos.service.ocr.PdfPageRenderer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentNormalizerTest {

    private final HealthOsProperties properties = new HealthOsProperties();

    /** 这些用例只关心归一化分支，把观测关掉即可，不必造真 mappers。 */
    private TracingService tracingOff() {
        HealthOsProperties tracingProps = new HealthOsProperties();
        tracingProps.getTracing().setEnabled(false);
        return new TracingService(mock(TraceRunMapper.class), mock(TraceSpanMapper.class),
                mock(TraceEventMapper.class), new DirectTraceWriter(), tracingProps);
    }

    private DocumentNormalizer normalizerWithOcrDisabled() {
        return new DocumentNormalizer(new DisabledOcrProvider(),
                new PdfPageRenderer(properties), properties, tracingOff());
    }

    @Test
    @DisplayName("纯文本原样接收，标记 sourceKind=text")
    void acceptsPlainText() {
        DocumentNormalizer.Normalized normalized = normalizerWithOcrDisabled()
                .normalize("四君子汤，益气健脾。".getBytes(StandardCharsets.UTF_8), "text/markdown", "a.md");
        assertThat(normalized.sourceKind()).isEqualTo("text");
        assertThat(normalized.pageCount()).isEqualTo(1);
        assertThat(normalized.markdown()).isEqualTo("四君子汤，益气健脾。");
        assertThat(normalized.ocrPages()).isZero();
    }

    @Test
    @DisplayName("无文本层 PDF 在未启用 OCR 时明确失败，而不是产出空文档")
    void rejectsScannedPdfWithoutOcr() {
        byte[] blankPdf = blankPdf();
        assertThatThrownBy(() -> normalizerWithOcrDisabled().normalize(blankPdf, "application/pdf", "scan.pdf"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("疑似扫描件")
                .hasMessageContaining("未启用 OCR");
    }

    @Test
    @DisplayName("图片在未启用 OCR 时明确失败")
    void rejectsImageWithoutOcr() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertThatThrownBy(() -> normalizerWithOcrDisabled().normalize(png, "image/png", "page.png"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("图片");
    }

    @Test
    @DisplayName("空内容直接拒绝")
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> normalizerWithOcrDisabled().normalize(new byte[0], "text/plain", "a.txt"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("类型判定同时认 content-type 与扩展名")
    void detectsTypeByContentTypeOrExtension() {
        DocumentNormalizer normalizer = normalizerWithOcrDisabled();
        assertThat(normalizer.isPdf("application/pdf", null)).isTrue();
        assertThat(normalizer.isPdf(null, "书.PDF")).isTrue();
        assertThat(normalizer.isPdf("text/plain", "a.txt")).isFalse();
        assertThat(normalizer.isImage("image/jpeg", null)).isTrue();
        assertThat(normalizer.isImage(null, "scan.TIFF")).isTrue();
        assertThat(normalizer.isImage(null, "a.md")).isFalse();
        assertThat(normalizer.isImage(null, null)).isFalse();
    }

    @Test
    @DisplayName("有效字符数忽略分页符与各类空白")
    void countsEffectiveChars() {
        assertThat(DocumentNormalizer.effectiveChars(null)).isZero();
        assertThat(DocumentNormalizer.effectiveChars("   \n\t\f\u3000")).isZero();
        assertThat(DocumentNormalizer.effectiveChars("四君子汤\f益气健脾")).isEqualTo(8);
    }

    /** 生成一页空白 PDF：PDFBox 抽取不出任何文字，等价于扫描件。 */
    private static byte[] blankPdf() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
