package com.kangheng.healthos.provider.ocr;

import com.kangheng.healthos.model.ocr.OcrResult;
import com.kangheng.healthos.spi.ocr.OcrException;
import com.kangheng.healthos.spi.ocr.OcrProvider;

/**
 * 未接入 OCR 时的占位实现。
 *
 * <p>与"静默返回空字符串"不同：这里明确抛错。因为在医疗文本场景下，
 * 把"没识别出字"当成"文档是空的"继续往下走，会产出一份看起来正常、实际无依据的报告。
 */
public class DisabledOcrProvider implements OcrProvider {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean healthy() {
        return false;
    }

    @Override
    public OcrResult recognize(byte[] image, String mediaType) {
        throw new OcrException(
                "当前未配置 OCR（healthos.ocr.provider=none），无法处理扫描件或图片。"
                        + "请设置 HEALTHOS_OCR_PROVIDER=generic 并指向可用的 OCR 服务后重试。");
    }
}
