package com.kangheng.healthos.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.AbstractResource;

/**
 * 带文件名的内存字节资源，用于 multipart 上传。
 *
 * <p>{@code ByteArrayResource} 的 {@code getFilename()} 返回 null，很多服务端
 * （含 RAGFlow、多数 OCR serving）会直接以 "No file part" 拒绝，因此需要这个薄封装。
 */
public class ByteArrayPart extends AbstractResource {

    private final byte[] content;
    private final String filename;

    public ByteArrayPart(byte[] content, String filename) {
        this.content = content;
        this.filename = filename;
    }

    @Override
    public String getDescription() {
        return "in-memory part " + filename + " (" + content.length + " bytes)";
    }

    @Override
    public String getFilename() {
        return filename;
    }

    @Override
    public long contentLength() {
        return content.length;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(content);
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
