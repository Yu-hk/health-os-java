package com.kangheng.healthos.service.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** 渲染全部页。 */
    public List<PageImage> render(byte[] pdf) {
        return render(pdf, null);
    }

    /**
     * 只渲染指定页（1-based），{@code pageNumbers} 为 null 时渲染全部。
     *
     * <p>按页混合时用得上：文本层达标的页保留无损 PDFBox 文本，只有不达标页才转图送 OCR。
     * 上限校验按"本次实际渲染页数"而不是 PDF 总页数——300 页的文本 PDF 只补 2 页 OCR
     * 不该因为总页数超过 {@code max-pages} 被拒。
     */
    public List<PageImage> render(byte[] pdf, Collection<Integer> pageNumbers) {
        HealthOsProperties.Ocr config = properties.getOcr();
        Set<Integer> wanted = pageNumbers == null ? null : new HashSet<>(pageNumbers);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int total = document.getNumberOfPages();
            int toRender = wanted == null ? total
                    : (int) wanted.stream().filter(n -> n >= 1 && n <= total).count();
            if (toRender > config.getMaxPages()) {
                throw BizException.badRequest(
                        "本次需 OCR " + toRender + " 页，超过上限 " + config.getMaxPages()
                                + " 页（PDF 共 " + total + " 页）。请拆分后上传，或调大 healthos.ocr.max-pages");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageImage> pages = new ArrayList<>(toRender);
            for (int page = 0; page < total; page++) {
                int pageNumber = page + 1;
                if (wanted != null && !wanted.contains(pageNumber)) {
                    continue;
                }
                BufferedImage image = renderer.renderImageWithDPI(page, config.getDpi(), ImageType.RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                pages.add(new PageImage(pageNumber, out.toByteArray(), image.getWidth(), image.getHeight()));
            }
            log.info("PDF 渲染完成 rendered={}/{} dpi={} pageNumbers={}",
                    pages.size(), total, config.getDpi(), wanted);
            return pages;
        } catch (IOException ex) {
            throw BizException.badRequest("PDF 渲染失败: " + ex.getMessage());
        }
    }
}
