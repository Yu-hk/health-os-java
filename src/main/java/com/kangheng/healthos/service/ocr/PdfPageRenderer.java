package com.kangheng.healthos.service.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.config.HealthOsProperties;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把 PDF 逐页渲染成图片交给 OCR。
 *
 * <p>两个刻意的取舍：
 * <ol>
 *   <li><b>逐页渲染、逐页识别</b>，不整篇转换。单页失败只影响该页，且页码到文本的映射天然正确——
 *       这直接支撑了后面引用回溯到"第几页第几字"。</li>
 *   <li><b>200 DPI + RGB 而非灰度</b>。中医典籍扫描件常见朱笔批注与彩色印章，
 *       灰度化会把批注与正文压成同一灰度层，反而降低区分度。</li>
 * </ol>
 */
@Component
public class PdfPageRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfPageRenderer.class);

    private final HealthOsProperties properties;

    public PdfPageRenderer(HealthOsProperties properties) {
        this.properties = properties;
    }

    /** 单页渲染结果：页码从 1 开始，与 {@code \f} 分页符的序号一致。 */
    public record PageImage(int pageNumber, byte[] png, int width, int height) {
    }

    public List<PageImage> render(byte[] pdf) {
        HealthOsProperties.Ocr config = properties.getOcr();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int total = document.getNumberOfPages();
            if (total > config.getMaxPages()) {
                throw BizException.badRequest(
                        "PDF 共 " + total + " 页，超过 OCR 上限 " + config.getMaxPages()
                                + " 页。请拆分后上传，或调大 healthos.ocr.max-pages");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageImage> pages = new ArrayList<>(total);
            for (int page = 0; page < total; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, config.getDpi(), ImageType.RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                pages.add(new PageImage(page + 1, out.toByteArray(), image.getWidth(), image.getHeight()));
            }
            log.info("PDF 渲染完成 pages={} dpi={}", total, config.getDpi());
            return pages;
        } catch (IOException ex) {
            throw BizException.badRequest("PDF 渲染失败: " + ex.getMessage());
        }
    }
}
