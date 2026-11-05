package com.kangheng.healthos.service.care;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.contract.care.CareContract;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 健康管理文档的 PDF 渲染。
 *
 * <p>对应原工程 {@code care_pdf.py}：<b>只渲染已落库的内容，绝不在导出时调用模型</b>。
 * 导出必须与"当时签发的那份"逐字一致，否则导出的就不是签发件。
 *
 * <p>与原工程的一处必要差异：reportlab 自带 {@code STSong-Light} 这个 CID 字体，
 * PDFBox 没有等价物，必须嵌入真实字体文件。字体是部署环境件（见
 * {@code healthos.care.pdf.font-path}），本类在启动时探测一次：
 * <ul>
 *   <li>探测成功 → 记下路径，{@link #status()} 返回 {@code available}；</li>
 *   <li>探测失败 → {@link #status()} 返回原因，导出接口报 503
 *       {@code CARE_PDF_UNAVAILABLE}。</li>
 * </ul>
 * 不做"静默降级成无字 PDF"：一份没有汉字的报告比报错更糟——它会被当成正式文件发出去。
 */
@Component
public class CarePdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(CarePdfRenderer.class);

    public static final String ERROR_UNAVAILABLE = "CARE_PDF_UNAVAILABLE";

    private static final float MARGIN_X = 42;
    private static final float MARGIN_TOP = 44;
    private static final float MARGIN_BOTTOM = 52;
    private static final float CONTENT_WIDTH = PDRectangle.A4.getWidth() - MARGIN_X * 2;

    private static final PdfColor BODY_COLOR = new PdfColor(0x26, 0x3b, 0x4d);
    private static final PdfColor HEADING_COLOR = new PdfColor(0x08, 0x7e, 0x78);
    private static final PdfColor MUTED_COLOR = new PdfColor(0x52, 0x6c, 0x7c);
    private static final PdfColor FOOTER_COLOR = new PdfColor(0x68, 0x80, 0x94);
    private static final PdfColor DRAFT_COLOR = new PdfColor(0xbf, 0x43, 0x52);

    /** 常见中文字体位置。Windows / Linux（文泉驿、Noto、AR PL）/ macOS。 */
    private static final List<String> CANDIDATES = List.of(
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/Deng.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/System/Library/Fonts/PingFang.ttc");

    /** 探测到的字体文件；{@code null} 表示不可用。 */
    private final File fontFile;
    private final String unavailableReason;

    public CarePdfRenderer(HealthOsProperties properties) {
        HealthOsProperties.Care.Pdf config = properties.getCare().getPdf();
        File resolved = null;
        String reason = null;
        if (!config.isEnabled()) {
            reason = "已通过 healthos.care.pdf.enabled=false 关闭";
        } else {
            List<File> candidates = new ArrayList<>();
            if (StringUtils.hasText(config.getFontPath())) {
                candidates.addAll(expand(config.getFontPath().strip()));
            }
            for (String path : CANDIDATES) {
                candidates.add(new File(path));
            }
            for (File candidate : candidates) {
                String failure = probe(candidate);
                if (failure == null) {
                    resolved = candidate;
                    break;
                }
                if (candidate.isFile()) {
                    // 文件存在但加载不了（例如 CFF 轮廓的 OTF），必须说出来，不能当成"没配"。
                    log.warn("中文字体无法用于 PDF 导出: {} ({})", candidate, failure);
                }
            }
            if (resolved == null) {
                reason = StringUtils.hasText(config.getFontPath())
                        ? "配置的字体不可用且系统路径未找到可用中文字体: " + config.getFontPath()
                        : "未找到可用中文字体，请配置 healthos.care.pdf.font-path";
            }
        }
        this.fontFile = resolved;
        this.unavailableReason = reason;
        if (resolved != null) {
            log.info("PDF 中文字体就绪: {}", resolved.getAbsolutePath());
        } else {
            log.warn("PDF 导出不可用: {}", reason);
        }
    }

    /** 配置可以指向文件或目录。 */
    private List<File> expand(String path) {
        File file = new File(path);
        List<File> files = new ArrayList<>();
        if (file.isDirectory()) {
            File[] children = file.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".ttf") || lower.endsWith(".ttc");
            });
            if (children != null) {
                files.addAll(List.of(children));
            }
        } else {
            files.add(file);
        }
        return files;
    }

    /** 返回 {@code null} 表示可用，否则返回失败原因。 */
    private String probe(File candidate) {
        if (candidate == null || !candidate.isFile() || !candidate.canRead()) {
            return "不存在或不可读";
        }
        String name = candidate.getName().toLowerCase();
        if (!name.endsWith(".ttf") && !name.endsWith(".ttc")) {
            return "只支持 .ttf / .ttc";
        }
        try (PDDocument document = new PDDocument()) {
            loadFont(document, candidate);
            return null;
        } catch (IOException | RuntimeException ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    public boolean available() {
        return fontFile != null;
    }

    /** 健康检查用：可读的状态描述，不含路径细节以外的敏感信息。 */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("available", available());
        status.put("font", fontFile == null ? null : fontFile.getName());
        status.put("reason", unavailableReason);
        return status;
    }

    /**
     * 渲染文档。
     *
     * @param view           文档视图（sections / tasks / signature 都已是落库内容）
     * @param sourceSnapshot 生成时冻结的体检快照，用于页眉的客户信息
     * @param document       落库实体，用于取完整度与签发时间（视图里没有的部分）
     */
    public byte[] render(CareContract.DocumentView view, Map<String, Object> sourceSnapshot) {
        if (fontFile == null) {
            throw new BizException(ERROR_UNAVAILABLE,
                    "PDF 导出不可用：" + unavailableReason, HttpStatus.SERVICE_UNAVAILABLE);
        }
        boolean report = !CareService.KIND_PLAN.equals(view.kind());
        String status = view.status() == null ? "DRAFT" : view.status().toUpperCase();
        boolean effective = "SIGNED".equals(status) || "ACTIVE".equals(status);
        String label = switch (status) {
            case "SIGNED" -> "已签发";
            case "ACTIVE" -> "已生效";
            case "SUPERSEDED" -> "已被新版本替代";
            default -> "草稿 · 待人工审核";
        };

        try (PDDocument document = new PDDocument()) {
            document.getDocumentInformation().setTitle("康衡健康管理报告");
            PDType0Font font = loadFont(document, fontFile);
            Layout layout = new Layout(document, font);

            layout.title(report ? "多学科健康评估报告" : "四周生活方式执行计划");
            layout.small("客户：" + text(basicInfo(sourceSnapshot, "name"))
                    + "　　修订：" + (view.revision() == null ? 1 : view.revision())
                    + "　　" + label);
            layout.small("来源体检：" + text(view.sourceExamId())
                    + " · 修订 " + (view.sourceExamRevision() == null ? "-" : view.sourceExamRevision()));
            if (!report) {
                if (StringUtils.hasText(view.sourceReportId())) {
                    layout.small("来源报告：" + view.sourceReportId()
                            + " · 修订 " + (view.sourceReportRevision() == null ? "-" : view.sourceReportRevision()));
                }
                if (StringUtils.hasText(view.startDate())) {
                    layout.small("计划开始：" + view.startDate());
                }
            }

            if (report) {
                renderReport(layout, view);
            } else {
                renderPlan(layout, view);
            }

            if (view.signature() != null) {
                CareContract.SignatureView signature = view.signature();
                layout.heading("签发记录");
                layout.small("确认人：" + text(signature.signer())
                        + "　　签发时间：" + text(signature.signedAt()));
                layout.small("审核意见：" + text(signature.reviewComment()));
                layout.small("内容指纹：" + text(signature.contentHash()));
            }

            layout.spacer(12);
            layout.heading("使用说明");
            layout.small(CareContract.DISCLAIMER);

            return layout.finish(effective ? label : "草稿，未签发 / 未生效",
                    view.revision() == null ? 1 : view.revision(), effective);
        } catch (IOException ex) {
            throw new BizException(ERROR_UNAVAILABLE,
                    "生成 PDF 失败: " + ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void renderReport(Layout layout, CareContract.DocumentView view) throws IOException {
        // 与原工程一致的三个"部分"标题，只在对应分节首次出现时打一次。
        Map<String, String> groups = new LinkedHashMap<>();
        groups.put("profile", "第一部分 · 客户基本体征与综合健康画像");
        groups.put("modern_assessment", "第二部分 · 多学科协同评估");
        groups.put("diet", "第三部分 · 四周生活方式干预原则");

        for (CareContract.SectionView section : view.sections()) {
            String group = groups.get(section.key());
            if (group != null) {
                layout.heading(group);
            }
            layout.heading(section.title() == null ? section.key() : section.title());
            layout.paragraph(section.present() && StringUtils.hasText(section.text())
                    ? section.text() : "此部分尚未生成完成。");
            refs(layout, section.metricRefs());
        }
    }

    private void renderPlan(Layout layout, CareContract.DocumentView view) throws IOException {
        for (CareContract.SectionView section : view.sections()) {
            layout.heading(section.title() == null ? section.key() : section.title());
            layout.paragraph(section.present() && StringUtils.hasText(section.text())
                    ? section.text() : "此部分尚未生成完成。");
            refs(layout, section.metricRefs());
        }
        if (view.checkin() != null) {
            CareContract.CheckinSummary checkin = view.checkin();
            layout.small("打卡进度：" + checkin.completed() + " / " + checkin.total()
                    + "（待完成 " + checkin.pending() + "）");
        }
    }

    /**
     * 体检依据。
     *
     * <p>只列 {@code metricRefs} 里的指标编码，<b>不显示当前指标值</b>：
     * 报告是冻结的，指标是会变的，把"今天的血压"写进"当时的报告"等于伪造依据。
     * 原工程能在 PDF 里带值，是因为它的 source_snapshot 里存了 indicators；
     * 本工程的快照只存了基本信息，宁可少显示，也不能显示错的值。
     */
    private void refs(Layout layout, List<String> metricRefs) throws IOException {
        if (metricRefs == null || metricRefs.isEmpty()) {
            return;
        }
        layout.small("本节引用指标：" + String.join("、", metricRefs));
    }

    private Object basicInfo(Map<String, Object> snapshot, String key) {
        if (snapshot == null) {
            return null;
        }
        Object basic = snapshot.get("basicInfo");
        if (basic instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? "未提供" : String.valueOf(value);
    }

    // ------------------------------------------------------------ 字体

    /**
     * 加载字体。
     *
     * <p>{@code .ttc} 是字体集合，取第一个可用的；子集化开启以控制体积。
     * 注意：<b>不能在返回前关闭 TrueTypeCollection</b>——子集化要到 {@code save()} 时才读字形，
     * 提前关闭会得到一个空字体。
     */
    private PDType0Font loadFont(PDDocument document, File file) throws IOException {
        if (file.getName().toLowerCase().endsWith(".ttc")) {
            TrueTypeCollection collection = new TrueTypeCollection(file);
            List<TrueTypeFont> fonts = new ArrayList<>();
            collection.processAllFonts(fonts::add);
            if (fonts.isEmpty()) {
                throw new IOException("字体集合为空: " + file.getName());
            }
            return PDType0Font.load(document, fonts.get(0), true);
        }
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            // 字体数据由 TrueTypeFont 全量读入内存，流可以立即关闭。
            return PDType0Font.load(document, in, true);
        }
    }

    // ------------------------------------------------------------ 排版

    /** 极简 RGB 颜色，避免为了三个常量引入 awt 依赖。 */
    private record PdfColor(int r, int g, int b) {
        float rf() {
            return r / 255f;
        }

        float gf() {
            return g / 255f;
        }

        float bf() {
            return b / 255f;
        }
    }

    /**
     * 逐行排版器。
     *
     * <p>中文按字断行（CJK 任意字符都可断开）；遇到西文单词时优先在空格处回退，
     * 避免把 {@code GLUCOSE} 拆成 {@code GLU / COSE}。
     */
    private static final class Layout {

        private final PDDocument document;
        private final PDType0Font font;
        private PDPageContentStream stream;
        private float y;
        private boolean firstPage = true;

        Layout(PDDocument document, PDType0Font font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - MARGIN_TOP;
            firstPage = false;
        }

        void title(String value) throws IOException {
            for (String line : wrap(value, 19f, CONTENT_WIDTH)) {
                write(line, 19f, BODY_COLOR, (PDRectangle.A4.getWidth() - width(line, 19f)) / 2);
            }
            y -= 6;
        }

        void heading(String value) throws IOException {
            y -= 6;
            for (String line : wrap(value, 13f, CONTENT_WIDTH)) {
                ensure(21f);
                write(line, 13f, HEADING_COLOR, MARGIN_X);
            }
        }

        void paragraph(String value) throws IOException {
            for (String line : wrap(value == null ? "未提供" : value, 10.5f, CONTENT_WIDTH)) {
                ensure(18f);
                write(line, 10.5f, BODY_COLOR, MARGIN_X);
            }
            y -= 10;
        }

        void small(String value) throws IOException {
            for (String line : wrap(value == null ? "未提供" : value, 9f, CONTENT_WIDTH)) {
                ensure(15f);
                write(line, 9f, MUTED_COLOR, MARGIN_X);
            }
        }

        void spacer(float height) {
            y -= height;
            if (y < MARGIN_BOTTOM) {
                try {
                    newPage();
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }

        private void ensure(float lineHeight) throws IOException {
            if (y - lineHeight < MARGIN_BOTTOM) {
                newPage();
            }
        }

        private void write(String line, float size, PdfColor color, float x) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color.rf(), color.gf(), color.bf());
            stream.newLineAtOffset(x, y - size);
            stream.showText(sanitize(line));
            stream.endText();
            y -= size * 1.7f;
        }

        private float width(String text, float size) {
            try {
                return font.getStringWidth(sanitize(text)) / 1000f * size;
            } catch (IOException ex) {
                return 0;
            }
        }

        /**
         * PDFBox 的 Type0 字体遇到不可映射字符会直接抛异常（一个字符毁掉整份文档），
         * 制表符与未配对代理项必须先归一。
         */
        private String sanitize(String value) {
            if (value == null) {
                return "";
            }
            StringBuilder safe = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '\t') {
                    safe.append("    ");
                } else if (ch == '\r') {
                    continue;
                } else if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
                    // 表情等增补平面字符在多数中文字体里没有字形，替换为占位符。
                    if (Character.isHighSurrogate(ch) && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1))) {
                        i++;
                    }
                    safe.append('?');
                } else if (ch < 0x20) {
                    safe.append(' ');
                } else {
                    safe.append(ch);
                }
            }
            return safe.toString();
        }

        private List<String> wrap(String text, float size, float maxWidth) {
            List<String> lines = new ArrayList<>();
            if (text == null) {
                return List.of("");
            }
            for (String raw : text.split("\n", -1)) {
                if (raw.isEmpty()) {
                    lines.add("");
                    continue;
                }
                StringBuilder current = new StringBuilder();
                float used = 0;
                int lastSpace = -1;
                for (int i = 0; i < raw.length(); i++) {
                    char ch = raw.charAt(i);
                    float charWidth = width(String.valueOf(ch), size);
                    if (used + charWidth > maxWidth && current.length() > 0) {
                        // 西文单词优先在空格处回退，避免把单词劈开。
                        if (lastSpace > 0 && current.length() - lastSpace <= 16) {
                            String carry = current.substring(lastSpace).stripLeading();
                            lines.add(current.substring(0, lastSpace).stripTrailing());
                            current.setLength(0);
                            used = 0;
                            current.append(carry);
                            used = width(carry, size);
                        } else {
                            lines.add(current.toString());
                            current.setLength(0);
                            used = 0;
                        }
                        lastSpace = -1;
                    }
                    current.append(ch);
                    used += charWidth;
                    if (ch == ' ') {
                        lastSpace = current.length();
                    }
                }
                lines.add(current.toString());
            }
            return lines;
        }

        /** 收尾：画页脚（页码、草稿标记）后输出字节。 */
        byte[] finish(String footerLabel, int revision, boolean effective) throws IOException {
            stream.close();
            stream = null;
            int pages = document.getNumberOfPages();
            for (int index = 0; index < pages; index++) {
                PDPage page = document.getPage(index);
                try (PDPageContentStream footer = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    footer.beginText();
                    footer.setFont(font, 9);
                    footer.setNonStrokingColor(FOOTER_COLOR.rf(), FOOTER_COLOR.gf(), FOOTER_COLOR.bf());
                    footer.newLineAtOffset(MARGIN_X, 25);
                    footer.showText(sanitize("康衡 HealthOS · " + footerLabel + " · 修订 " + revision));
                    footer.endText();

                    String pageLabel = "第 " + (index + 1) + " 页 / 共 " + pages + " 页";
                    footer.beginText();
                    footer.setFont(font, 9);
                    footer.setNonStrokingColor(FOOTER_COLOR.rf(), FOOTER_COLOR.gf(), FOOTER_COLOR.bf());
                    footer.newLineAtOffset(PDRectangle.A4.getWidth() - MARGIN_X
                            - width(pageLabel, 9), 25);
                    footer.showText(sanitize(pageLabel));
                    footer.endText();

                    if (!effective) {
                        // 草稿标记放右上角，与居中标题错开，避免盖住第一行正文。
                        footer.beginText();
                        footer.setFont(font, 9);
                        footer.setNonStrokingColor(DRAFT_COLOR.rf(), DRAFT_COLOR.gf(), DRAFT_COLOR.bf());
                        footer.newLineAtOffset(PDRectangle.A4.getWidth() - MARGIN_X
                                - width(footerLabel, 9), PDRectangle.A4.getHeight() - 28);
                        footer.showText(sanitize(footerLabel));
                        footer.endText();
                    }
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** 供配置示例与文档引用。 */
    public static List<String> candidatePaths() {
        return CANDIDATES;
    }
}
