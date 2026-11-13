package com.kangheng.healthos.service.classics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.model.ocr.OcrResult;
import com.kangheng.healthos.service.ocr.OcrTextAssembler;
import com.kangheng.healthos.service.ocr.PdfPageRenderer;
import com.kangheng.healthos.service.tracing.TracingService;
import com.kangheng.healthos.spi.ocr.OcrException;
import com.kangheng.healthos.spi.ocr.OcrProvider;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 文档归一化：把上传内容转成统一 markdown，并在 PDF 页间插入换页符供页码回溯。
 *
 * <p>PDF 有四种结果，与原工程 {@code normalize_or_ocr} 节点对齐但判定更细：
 * <ol>
 *   <li><b>pdf-text</b>：逐页文本层都达标，直接用 PDFBox 抽取，最快且无损；</li>
 *   <li><b>pdf-ocr</b>：整篇都没有可信文本层（扫描件）或 {@code mode=ocr} 强制重扫，逐页 OCR；</li>
 *   <li><b>pdf-mixed</b>：混合 PDF，达标页保留 PDFBox 文本，只把不达标页送 OCR，按原页码合并；</li>
 *   <li><b>image-ocr / text</b>：图片直接识别、纯文本原样接收。</li>
 * </ol>
 *
 * <p><b>为什么必须逐页判定</b>：早期实现把整篇文本拼成一个字符串、总数 ≥20 就走文本层。
 * 于是"前几页正文 + 后几页扫描图"会因总数达标而漏掉扫描页；反过来 10 页扫描件每页
 * 只有"第1页"三个字也能凑够 20 字。原工程 {@code document_ocr.py} 的做法是逐页算
 * 非空白字符、{@code all(页达标)} 收口，并用 {@code page.images} 判断低文本页是不是扫描页，
 * 这里沿用同一思路：{@link #pageUsable} + {@link #hasImages}。
 *
 * <p>有效字符只数汉字 / 字母 / 数字：页码、水印、标点、控制字符都不该被当作"正文存在"的证据。
 */
@Component
public class DocumentNormalizer {

    private static final Logger log = LoggerFactory.getLogger(DocumentNormalizer.class);

    private static final char PAGE_BREAK = '\f';

    /** Form XObject 允许递归查找图像的层数，防止畸形 PDF 造成深递归。 */
    private static final int MAX_XOBJECT_DEPTH = 3;

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
     * @param sourceKind text / pdf-text / pdf-ocr / pdf-mixed / image-ocr
     * @param engine     文本来源引擎（pdfbox、OCR 实现名，或混合时的 pdfbox+OCR），写入流水线 detail
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

    /** 单页文本层快照：页码（1-based）、抽取文本、有效字符数、页面是否含图像。 */
    private record PageText(int pageNumber, String text, int effective, boolean hasImages) {
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
        HealthOsProperties.Ocr config = properties.getOcr();
        String mode = modeOf(config);
        List<PageText> pages = extractPages(content);
        int total = pages.stream().mapToInt(PageText::effective).sum();
        boolean wholeSparse = total < config.getTextLayerMinChars();
        List<PageText> weak = pages.stream().filter(page -> !pageUsable(page, config)).toList();
        boolean usable = !wholeSparse && weak.isEmpty();

        if (usable && !"ocr".equals(mode)) {
            return new Normalized(joinText(pages.stream().map(PageText::text).toList()),
                    "pdf-text", pages.size(), "pdfbox", 0);
        }
        if ("text".equals(mode)) {
            throw BizException.badRequest("PDF 文本层不足（共 " + pages.size() + " 页，整篇有效字符 "
                    + total + "，未达标页 " + pageNumbers(weak) + "，每页下限 "
                    + config.getTextLayerMinCharsPerPage() + "），当前 mode=text 不允许降级 OCR。"
                    + "请改用 mode=auto 补 OCR，或 mode=ocr 整篇重扫");
        }
        if (usable) {
            log.info("mode=ocr 强制整篇重扫 pages={}（文本层本可用，整篇有效字符 {}）", pages.size(), total);
        } else {
            log.info("PDF 文本层不可全信 pages={} totalChars={} weakPages={} perPageMin={} mode={}",
                    pages.size(), total, pageNumbers(weak),
                    config.getTextLayerMinCharsPerPage(), mode);
        }
        return normalizeWithOcr(content, pages, "ocr".equals(mode), wholeSparse, config);
    }

    /**
     * 把 PDF 逐页抽成文本层快照，同时记录该页是否含图像对象。
     *
     * <p>保留页级结构是关键：只保留一个拼接字符串的话，后面就无法知道"哪几页不可信"。
     */
    private List<PageText> extractPages(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = document.getNumberOfPages();
            List<PageText> pages = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).strip();
                pages.add(new PageText(page, text, effectiveChars(text),
                        hasImages(document.getPage(page - 1).getResources(), 0)));
            }
            return pages;
        } catch (IOException ex) {
            throw BizException.badRequest("PDF 解析失败: " + ex.getMessage());
        }
    }

    /** 单页文本层是否可信：字数够，或者被显式允许且确实没有图像（书名页 / 空白分隔页）。 */
    private static boolean pageUsable(PageText page, HealthOsProperties.Ocr config) {
        if (page.effective() >= config.getTextLayerMinCharsPerPage()) {
            return true;
        }
        return config.isAllowSparseTextPages() && !page.hasImages();
    }

    /** 页面是否含图像 XObject；Form XObject 内嵌的图也有限递归查一层。 */
    private static boolean hasImages(PDResources resources, int depth) {
        if (resources == null || depth > MAX_XOBJECT_DEPTH) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject;
            try {
                xobject = resources.getXObject(name);
            } catch (IOException ex) {
                log.warn("读取 PDF XObject 失败 name={}: {}", name.getName(), ex.getMessage());
                continue;
            }
            if (xobject instanceof PDImageXObject) {
                return true;
            }
            if (xobject instanceof PDFormXObject form
                    && hasImages(form.getResources(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对不达标页（或强制时的全部页）逐页 OCR，再按原页码与文本层合并。
     *
     * <p>两条防漏页规则：含图像的页 OCR 结果为空时整篇失败，不交出"看起来完整实际缺页"的正文；
     * 无图且 OCR 没有带来增量的页（空白分隔页 / 短标题页）保留无损 PDFBox 文本，不做无谓降质。
     */
    private Normalized normalizeWithOcr(byte[] content, List<PageText> pages,
                                        boolean forceAll, boolean wholeSparse,
                                        HealthOsProperties.Ocr config) {
        Set<Integer> ocrPageNumbers = new LinkedHashSet<>();
        for (PageText page : pages) {
            if (forceAll || wholeSparse || !pageUsable(page, config)) {
                ocrPageNumbers.add(page.pageNumber());
            }
        }
        if (ocrPageNumbers.isEmpty()) {
            throw new IllegalStateException("文本层判定为不可用，却没有选出需要 OCR 的页");
        }
        if (!ocrProvider.enabled()) {
            throw BizException.badRequest("PDF 文本层不足（共 " + pages.size() + " 页，需 OCR 的页："
                    + ocrPageNumbers + "，疑似扫描件或混合 PDF），且当前未启用 OCR。"
                    + "请配置 HEALTHOS_OCR_PROVIDER 与 HEALTHOS_OCR_BASE_URL 后重试，或先转成文本再上传");
        }

        Map<Integer, String> recognizedByPage = new HashMap<>();
        for (PdfPageRenderer.PageImage image : pageRenderer.render(content, ocrPageNumbers)) {
            recognizedByPage.put(image.pageNumber(), recognizePage(image));
        }

        List<String> merged = new ArrayList<>(pages.size());
        int ocrChars = 0;
        for (PageText page : pages) {
            if (!ocrPageNumbers.contains(page.pageNumber())) {
                merged.add(page.text());
                continue;
            }
            String recognized = recognizedByPage.getOrDefault(page.pageNumber(), "");
            int recognizedChars = effectiveChars(recognized);
            if (page.hasImages() && recognizedChars == 0) {
                throw BizException.badRequest("第 " + page.pageNumber()
                        + " 页含图像，但 OCR 未识别出任何文字；为避免交出缺页正文，整篇判定为失败。"
                        + "请检查扫描清晰度或 OCR 服务");
            }
            if (!forceAll && !page.hasImages() && recognizedChars <= page.effective()) {
                log.debug("第 {} 页 OCR 无增量（{} <= {}），保留 PDFBox 文本层",
                        page.pageNumber(), recognizedChars, page.effective());
                merged.add(page.text());
                continue;
            }
            ocrChars += recognizedChars;
            merged.add(recognized.strip());
        }

        if ((forceAll || wholeSparse) && ocrChars < config.getOcrMinResultChars()) {
            throw BizException.badRequest("OCR 结果有效字符 " + ocrChars + " < 下限 "
                    + config.getOcrMinResultChars() + "（" + ocrPageNumbers.size()
                    + " 页）。请检查扫描清晰度或 OCR 服务是否正常");
        }
        String text = joinText(merged);
        if (effectiveChars(text) < config.getTextLayerMinChars()) {
            throw BizException.badRequest("归一化后正文有效字符不足 "
                    + config.getTextLayerMinChars() + "（共 " + pages.size() + " 页），疑似空文档或乱码文本层");
        }

        boolean mixed = ocrPageNumbers.size() < pages.size();
        String sourceKind = mixed ? "pdf-mixed" : "pdf-ocr";
        String engine = mixed ? "pdfbox+" + ocrProvider.name() : ocrProvider.name();
        log.info("PDF 归一化完成 pages={} textPages={} ocrPages={} ocrChars={} engine={} sourceKind={}",
                pages.size(), pages.size() - ocrPageNumbers.size(),
                ocrPageNumbers.size(), ocrChars, engine, sourceKind);
        return new Normalized(text, sourceKind, pages.size(), engine, ocrPageNumbers.size());
    }

    // ---------------------------------------------------------------- 图片

    private Normalized normalizeImage(byte[] content, String contentType) {
        if (!ocrProvider.enabled()) {
            throw BizException.badRequest("上传的是图片，当前未启用 OCR（healthos.ocr.provider=none），无法提取文字");
        }
        OcrResult result = recognizeWithRetry(content, contentType, "图片");
        String text = OcrTextAssembler.assemble(result.lines());
        HealthOsProperties.Ocr config = properties.getOcr();
        if (effectiveChars(text) < config.getOcrMinResultChars()) {
            throw BizException.badRequest("OCR 从该图片识别出的有效字符不足 "
                    + config.getOcrMinResultChars() + "，请检查图片清晰度或 OCR 服务");
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

    /**
     * 有效字符数：只统计汉字 / 字母 / 数字（按 Unicode code point，兼容扩展汉字）。
     *
     * <p>早期实现只去掉空白，于是页码"第1页"、水印、乱码控制符都被算作正文，
     * 页数越多越容易把扫描件误判成文本 PDF。
     */
    static int effectiveChars(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return (int) text.codePoints().filter(cp -> Character.isLetterOrDigit(cp)).count();
    }

    private static String modeOf(HealthOsProperties.Ocr config) {
        String mode = config.getMode() == null ? "auto" : config.getMode().trim().toLowerCase();
        if (!"auto".equals(mode) && !"text".equals(mode) && !"ocr".equals(mode)) {
            throw new IllegalStateException("不支持的 healthos.ocr.mode=" + mode + "（可选 auto/text/ocr）");
        }
        return mode;
    }

    private static List<Integer> pageNumbers(List<PageText> pages) {
        return pages.stream().map(PageText::pageNumber).toList();
    }

    private static String joinText(List<String> pageTexts) {
        StringBuilder markdown = new StringBuilder();
        for (int i = 0; i < pageTexts.size(); i++) {
            String text = pageTexts.get(i);
            markdown.append(text == null ? "" : text.strip());
            if (i < pageTexts.size() - 1) {
                markdown.append('\n').append(PAGE_BREAK).append('\n');
            }
        }
        return markdown.toString().strip();
    }
}
