package com.kangheng.healthos.spi.ocr;

import com.kangheng.healthos.model.ocr.OcrResult;

/**
 * OCR 能力抽象。
 *
 * <p>原项目直接调百度 Paddle OCR 的 HTTP 服务；这里把"怎么识别"抽成接口，
 * 让流水线只依赖 {@code recognize}，换供应商（Paddle / 自建 / 云端）不改业务代码。
 *
 * <p>约定：{@link #recognize} 只处理<b>单页图片</b>。PDF 的分页渲染由业务层的
 * {@code service/ocr/PdfPageRenderer} 负责，这样 OCR 服务方不需要理解 PDF。
 */
public interface OcrProvider {

    /** 实现名，写入流水线节点 detail，便于事后追溯"这份文档是哪套 OCR 出的字"。 */
    String name();

    /** 未配置时为 false，此时调用 {@link #recognize} 会抛出可读的业务异常而不是空指针。 */
    boolean enabled();

    /** 识别单页图片（PNG/JPEG）。失败抛 {@link OcrException}。 */
    OcrResult recognize(byte[] image, String mediaType);

    /** 供 /health/dependencies 探测外部 OCR 服务是否真的可达。 */
    default boolean healthy() {
        return enabled();
    }
}
