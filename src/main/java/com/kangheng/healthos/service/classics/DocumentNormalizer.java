package com.kangheng.healthos.service.classics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.spi.ocr.OcrException;
import com.kangheng.healthos.spi.ocr.OcrProvider;
import com.kangheng.healthos.model.ocr.OcrResult;
import com.kangheng.healthos.service.ocr.OcrTextAssembler;
import com.kangheng.healthos.service.ocr.PdfPageRenderer;
import com.kangheng.healthos.service.tracing.TracingService;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 文档归一化：把上传内容转成统一 markdown，并在 PDF 页间插入换页符供页码回溯。
 *
 * <p>三条分支，与源工程 {@code normalize_or_ocr} 节点对齐：
 * <ol>
 *   <li><b>文本层可用</b>：直接用 PDFBox 抽取，最快且无损；</li>
 *   <li><b>扫描件 / 图片</b>：PDF 逐页渲染或图片直接送 OCR；</li>
 *   <li><b>纯文本</b>：原样接收。</li>
 * </ol>
 *
 * <p>判定"是否扫描件"用的是<b>有效字符数下限</b>而不是"有无文本"：很多扫描 PDF
 * 带一层由扫描软件生成的空文本或极短乱码层，只判断存在性会走进错误分支，
 * 抽出一份看起来正常、实际无内容的文档。
 */
@Component
public class DocumentNormalizer {

    private static final Logger log = LoggerFactory.getLogger(DocumentNormalizer.class);

    private static final char PAGE_BREAK = '\f';

    private final OcrProvider ocrProvider;
    private final PdfPageRenderer pageRenderer;
    private final HealthOsProperties properties;
    private final TracingService tracing;

    public DocumentNormalizer(OcrProvider ocrProvider,
                              PdfPageRenderer pageRenderer,
                              HealthOsProperties properties,
                              TracingService tracing) {
        this.ocrProvider = ocrProvider;
        this.pageRenderer = pageRenderer;
        this.properties = properties;
        this.tracing = tracing;
    }

    /**
     * @param sourceKind text / pdf-text / pdf-ocr / image-ocr
     * @param engine     文本来源引擎（pdfbox 或 OCR 实现名），写入流水线 detail 便于追溯
     * @param ocrPages   实际跑过 OCR 的页数，0 表示未走 OCR
     */
    public record Normalized(String markdown, String sourceKind, int pageCount, String engine, int ocrPages) {

        public Normalized(String markdown, String sourceKind, int pageCount) {
            this(markdown, sourceKind, pageCount, "none", 0);
        }

        public boolean hasText() {
            return markdown != null && !markdown.isBlank();
        }
    }

    private record TextLayer(String text, int pages) {
    }

    public Normalized normalize(byte[] content, String contentType, String filename) {
        if (content == null || content.length == 0) {
            throw BizException.badRequest("上传内容为空");
        }
        if (isPdf(contentType, filename)) {
            return normalizePdf(content);
        }
        if (isImage(contentType, filename)) {
            return normalizeImage(content, contentType);
        }
        String text = new String(content, StandardCharsets.UTF_8);
        return new Normalized(text, "text", 1);
    }

    boolean isPdf(String contentType, String filename) {
        if (contentType != null && contentType.toLowerCase().contains("pdf")) {
            return true;
        }
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    boolean isImage(String contentType, String filename) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return true;
        }
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase();
        for (String suffix : List.of(".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp")) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- PDF

    private Normalized normalizePdf(byte[] content) {
        TextLayer layer = extractTextLayer(content);
        int effective = effectiveChars(layer.text());
        if (effective >= properties.getOcr().getTextLayerMinChars()) {
            return new Normalized(layer.text(), "pdf-text", layer.pages(), "pdfbox", 0);
        }
        log.info("PDF 文本层有效字符 {} < 阈值 {}，判定为扫描件，转 OCR pages={}",
                effective, properties.getOcr().getTextLayerMinChars(), layer.pages());
        return normalizeScannedPdf(content, layer.pages());
    }

    private TextLayer extractTextLayer(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder markdown = new StringBuilder();
            int pages = document.getNumberOfPages();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                markdown.append(stripper.getText(document));
                if (page < pages) {
                    markdown.append('\n').append(PAGE_BREAK).append('\n');
                }
            }
            return new TextLayer(markdown.toString().strip(), pages);
        } catch (IOException ex) {
            throw BizException.badRequest("PDF 解析失败: " + ex.getMessage());
        }
    }

    private Normalized normalizeScannedPdf(byte[] content, int pageCount) {
        if (!ocrProvider.enabled()) {
            throw BizException.badRequest(
                    "PDF 没有可用的文本层（共 " + pageCount + " 页，疑似扫描件），且当前未启用 OCR。"
                            + "请配置 HEALTHOS_OCR_PROVIDER 与 HEALTHOS_OCR_BASE_URL 后重试，或先转成文本再上传");
        }
        List<PdfPageRenderer.PageImage> images = pageRenderer.render(content);
        StringBuilder markdown = new StringBuilder();
        int recognizedPages = 0;
        int emptyPages = 0;
        for (int i = 0; i < images.size(); i++) {
            PdfPageRenderer.PageImage image = images.get(i);
            String pageText = recognizePage(image);
            if (pageText.isBlank()) {
                emptyPages++;
            } else {
                recognizedPages++;
            }
            markdown.append(pageText);
            if (i < images.size() - 1) {
                markdown.append('\n').append(PAGE_BREAK).append('\n');
            }
        }
        String text = markdown.toString().strip();
        if (effectiveChars(text) == 0) {
            throw BizException.badRequest("OCR 未从该 PDF 识别出任何文本（" + images.size()
                    + " 页全部为空）。请检查扫描清晰度或 OCR 服务是否正常");
        }
        log.info("扫描件 OCR 完成 pages={} recognized={} empty={} engine={}",
                images.size(), recognizedPages, emptyPages, ocrProvider.name());
        return new Normalized(text, "pdf-ocr", images.size(), ocrProvider.name(), images.size());
    }

    // ---------------------------------------------------------------- 图片

    private Normalized normalizeImage(byte[] content, String contentType) {
        if (!ocrProvider.enabled()) {
            throw BizException.badRequest("上传的是图片，当前未启用 OCR（healthos.ocr.provider=none），无法提取文字");
        }
        OcrResult result = recognizeWithRetry(content, contentType, "图片");
        String text = OcrTextAssembler.assemble(result.lines());
        if (text.isBlank()) {
            throw BizException.badRequest("OCR 未从该图片识别出任何文本，请检查图片清晰度或 OCR 服务");
        }
        return new Normalized(text, "image-ocr", 1, ocrProvider.name(), 1);
    }

    // ---------------------------------------------------------------- OCR 调用

    private String recognizePage(PdfPageRenderer.PageImage image) {
        String mediaType = "image/png";
        OcrResult result = recognizeWithRetry(image.png(), mediaType, "第 " + image.pageNumber() + " 页");
        return OcrTextAssembler.assemble(result.lines());
    }

    /**
     * 单页识别 + 一次重试。
     *
     * <p>为什么允许重试却不在缺页时继续：单页超时/5xx 多半是瞬时抖动，重试一次能挽回；
     * 但重试后仍失败的页会让正文出现缺口，而页码仍是对的——这种"看起来完整实际缺页"的文档
     * 在医疗场景比直接失败更危险，因此抛出让整篇失败。
     */
    private OcrResult recognizeWithRetry(byte[] image, String mediaType, String where) {
        try {
            return recognize(image, mediaType, where, 1);
        } catch (OcrException first) {
            if (!first.isRetryable()) {
                throw BizException.badRequest("OCR 失败（" + where + "）: " + first.getMessage());
            }
            log.warn("OCR 首次失败，重试一次（{}）: {}", where, first.getMessage());
            try {
                return recognize(image, mediaType, where, 2);
            } catch (OcrException second) {
                throw BizException.badRequest("OCR 重试后仍失败（" + where + "）: " + second.getMessage());
            }
        }
    }

    /** 每次调用单独一个 span：重试这件事必须能在耗时里看出来。 */
    private OcrResult recognize(byte[] image, String mediaType, String where, int attempt) {
        return tracing.span("ocr.recognize", "ocr", "ocr",
                Map.of("engine", ocrProvider.name(), "bytes", image.length,
                        "attempt", attempt, "where", where),
                () -> ocrProvider.recognize(image, mediaType));
    }

    // ---------------------------------------------------------------- 工具

    /** 有效字符数：剔除分页符与空白后的长度。用于区分"真空文档"与"只有一层空文本"。 */
    static int effectiveChars(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int count = 0;
        for (char ch : text.toCharArray()) {
            if (ch == PAGE_BREAK || Character.isWhitespace(ch) || ch == '\u3000') {
                continue;
            }
            count++;
        }
        return count;
    }
}
