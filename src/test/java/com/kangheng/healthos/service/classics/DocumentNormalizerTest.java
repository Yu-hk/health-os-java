package com.kangheng.healthos.service.classics;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.mapper.tracing.TraceEventMapper;
import com.kangheng.healthos.mapper.tracing.TraceRunMapper;
import com.kangheng.healthos.mapper.tracing.TraceSpanMapper;
import com.kangheng.healthos.model.ocr.OcrResult;
import com.kangheng.healthos.provider.ocr.DisabledOcrProvider;
import com.kangheng.healthos.provider.tracing.DirectTraceWriter;
import com.kangheng.healthos.service.ocr.PdfPageRenderer;
import com.kangheng.healthos.service.tracing.TracingService;
import com.kangheng.healthos.spi.ocr.OcrProvider;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DocumentNormalizerTest {

    /** OCR 桩返回的固定正文，有效字符远大于单页文本层的 108 字。 */
    private static final String OCR_TEXT =
            "OCR recognized hemoglobin 130 and glucose 51 from the scanned page";

    private static final String PDF_TYPE = "application/pdf";

    private final HealthOsProperties properties = new HealthOsProperties();

    /** 这些用例只关心归一化分支，把观测关掉即可，不必造真 mappers。 */
    private TracingService tracingOff() {
        HealthOsProperties tracingProps = new HealthOsProperties();
        tracingProps.getTracing().setEnabled(false);
        return new TracingService(mock(TraceRunMapper.class), mock(TraceSpanMapper.class),
                mock(TraceEventMapper.class), new DirectTraceWriter(), tracingProps);
    }

    private DocumentNormalizer normalizerWithOcrDisabled() {
        return normalizer(new DisabledOcrProvider(), properties);
    }

    private DocumentNormalizer normalizer(OcrProvider provider, HealthOsProperties props) {
        // 只影响渲染耗时，不影响文本层判定
        props.getOcr().setDpi(36);
        return new DocumentNormalizer(provider, new PdfPageRenderer(props), props, tracingOff());
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
        assertThatThrownBy(() -> normalizerWithOcrDisabled().normalize(blankPdf, PDF_TYPE, "scan.pdf"))
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
    @DisplayName("有效字符只数汉字/字母/数字，页码标点水印不计")
    void countsEffectiveChars() {
        assertThat(DocumentNormalizer.effectiveChars(null)).isZero();
        assertThat(DocumentNormalizer.effectiveChars("   \n\t\f\u3000")).isZero();
        assertThat(DocumentNormalizer.effectiveChars("四君子汤\f益气健脾")).isEqualTo(8);
        assertThat(DocumentNormalizer.effectiveChars("第1页，第2页；第3页")).isEqualTo(9);
        assertThat(DocumentNormalizer.effectiveChars("？？？***\u0000")).isZero();
    }

    @Test
    @DisplayName("回归：整篇字符数达标也不能把逐页只有页码的 PDF 当成文本层")
    void perPageThresholdBeatsWholeDocumentTotal() {
        List<String> pageTexts = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            pageTexts.add("Page " + i);
        }
        StubOcr ocr = new StubOcr(OCR_TEXT);
        DocumentNormalizer.Normalized normalized = normalizer(ocr, new HealthOsProperties())
                .normalize(pdf(pageTexts, Set.of()), PDF_TYPE, "only-page-numbers.pdf");
        assertThat(normalized.sourceKind()).isEqualTo("pdf-ocr");
        assertThat(normalized.ocrPages()).isEqualTo(10);
        assertThat(ocr.callCount).isEqualTo(10);
    }

    @Test
    @DisplayName("每页文本层都达标时完全不走 OCR，保留换页符")
    void keepsTextLayerWhenEveryPageIsRich() {
        StubOcr ocr = new StubOcr(OCR_TEXT);
        byte[] source = pdf(List.of(lines(6), lines(6)), Set.of());
        DocumentNormalizer.Normalized normalized = normalizer(ocr, new HealthOsProperties())
                .normalize(source, PDF_TYPE, "text.pdf");
        assertThat(normalized.sourceKind()).isEqualTo("pdf-text");
        assertThat(normalized.engine()).isEqualTo("pdfbox");
        assertThat(normalized.ocrPages()).isZero();
        assertThat(ocr.callCount).isZero();
        assertThat(normalized.markdown()).contains("\f");
    }

    @Test
    @DisplayName("混合 PDF：只对不达标页 OCR，按原页码合并，清晰页不被降质")
    void mixedPdfOnlyOcrsWeakPages() {
        StubOcr ocr = new StubOcr(OCR_TEXT);
        byte[] source = pdf(List.of(lines(6), "Page 2"), Set.of(2));
        DocumentNormalizer.Normalized normalized = normalizer(ocr, new HealthOsProperties())
                .normalize(source, PDF_TYPE, "mixed.pdf");
        assertThat(normalized.sourceKind()).isEqualTo("pdf-mixed");
        assertThat(normalized.engine()).isEqualTo("pdfbox+stub");
        assertThat(normalized.ocrPages()).isEqualTo(1);
        assertThat(ocr.callCount).isEqualTo(1);
        String[] pages = normalized.markdown().split("\f");
        assertThat(pages).hasSize(2);
        assertThat(pages[0]).contains("HGB 130");
        assertThat(pages[0]).doesNotContain("OCR recognized");
        assertThat(pages[1]).contains("OCR recognized");
    }

    @Test
    @DisplayName("allowSparseTextPages 豁免无图短页，但绝不豁免有图页")
    void sparseExemptionNeverAppliesToPagesWithImages() {
        HealthOsProperties props = new HealthOsProperties();
        props.getOcr().setAllowSparseTextPages(true);
        StubOcr ocr = new StubOcr(OCR_TEXT);

        DocumentNormalizer.Normalized noImage = normalizer(ocr, props)
                .normalize(pdf(List.of(lines(6), "Page 2"), Set.of()), PDF_TYPE, "sparse.pdf");
        assertThat(noImage.sourceKind()).isEqualTo("pdf-text");
        assertThat(ocr.callCount).isZero();

        DocumentNormalizer.Normalized withImage = normalizer(ocr, props)
                .normalize(pdf(List.of(lines(6), "Page 2"), Set.of(2)), PDF_TYPE, "sparse-image.pdf");
        assertThat(withImage.sourceKind()).isEqualTo("pdf-mixed");
        assertThat(withImage.ocrPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("mode=text 遇到不可信文本层时明确报错，不偷偷降级 OCR")
    void modeTextRejectsWeakTextLayer() {
        HealthOsProperties props = new HealthOsProperties();
        props.getOcr().setMode("text");
        byte[] source = pdf(List.of(lines(6), "Page 2"), Set.of(2));
        assertThatThrownBy(() -> normalizer(new DisabledOcrProvider(), props)
                .normalize(source, PDF_TYPE, "mixed.pdf"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("mode=text")
                .hasMessageContaining("未达标页");
    }

    @Test
    @DisplayName("mode=ocr 强制整篇重扫，即使文本层完全可用")
    void modeOcrForcesFullRescan() {
        HealthOsProperties props = new HealthOsProperties();
        props.getOcr().setMode("ocr");
        StubOcr ocr = new StubOcr(OCR_TEXT);
        byte[] source = pdf(List.of(lines(6), lines(6)), Set.of());
        DocumentNormalizer.Normalized normalized = normalizer(ocr, props)
                .normalize(source, PDF_TYPE, "text.pdf");
        assertThat(normalized.sourceKind()).isEqualTo("pdf-ocr");
        assertThat(normalized.ocrPages()).isEqualTo(2);
        assertThat(ocr.callCount).isEqualTo(2);
    }

    @Test
    @DisplayName("OCR 结果低于下限时明确失败，不产出半截文档")
    void rejectsTooShortOcrResult() {
        HealthOsProperties props = new HealthOsProperties();
        props.getOcr().setMode("ocr");
        byte[] source = pdf(List.of(lines(6)), Set.of());
        assertThatThrownBy(() -> normalizer(new StubOcr("OK"), props)
                .normalize(source, PDF_TYPE, "scan.pdf"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("OCR 结果有效字符");
    }

    @Test
    @DisplayName("含图像页 OCR 为空时整篇失败，避免交出看起来完整的缺页正文")
    void rejectsEmptyOcrOnImagePage() {
        byte[] source = pdf(List.of(lines(6), "Page 2"), Set.of(2));
        assertThatThrownBy(() -> normalizer(new StubOcr(""), new HealthOsProperties())
                .normalize(source, PDF_TYPE, "mixed.pdf"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第 2 页")
                .hasMessageContaining("含图像");
    }

    /** 造真实 PDF：每页可写 ASCII 文本，并可挂一个图像 XObject 模拟扫描页。 */
    private static byte[] pdf(List<String> pageTexts, Set<Integer> imagePages) {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pageTexts.size(); i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    if (imagePages.contains(i + 1)) {
                        BufferedImage pixel = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
                        stream.drawImage(LosslessFactory.createFromImage(document, pixel), 20, 20, 2, 2);
                    }
                    String text = pageTexts.get(i);
                    if (!text.isEmpty()) {
                        stream.beginText();
                        stream.setFont(font, 9);
                        stream.newLineAtOffset(20, 750);
                        for (String line : text.split("\n")) {
                            stream.showText(line);
                            stream.newLineAtOffset(0, -12);
                        }
                        stream.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 每行 18 个有效字符，6 行 = 108，稳过每页 80 的阈值。 */
    private static String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append("HGB 130 g/L. GLU 5.1 mmol/L.");
        }
        return text.toString();
    }

    /** 生成一页空白 PDF：PDFBox 抽取不出任何文字，等价于扫描件。 */
    private static byte[] blankPdf() {
        return pdf(List.of(""), Set.of());
    }

    private static final class StubOcr implements OcrProvider {

        private final String text;
        private int callCount;

        private StubOcr(String text) {
            this.text = text;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public OcrResult recognize(byte[] image, String mediaType) {
            callCount++;
            if (text.isBlank()) {
                return OcrResult.empty("stub");
            }
            return new OcrResult("stub",
                    List.of(new OcrResult.Line(text, 0.99, 0, 0, 100, 20)));
        }
    }
}
