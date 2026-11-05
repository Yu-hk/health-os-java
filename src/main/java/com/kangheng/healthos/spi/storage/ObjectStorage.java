package com.kangheng.healthos.spi.storage;

import java.util.List;

/**
 * 对象存储抽象。原项目用 MinIO，这里先提供本地实现，
 * 换成 MinIO 只需新增一个实现类并改 {@code healthos.storage.type}（见 README 扩展点）。
 *
 * <p>两个写入入口的区别很重要：
 * <ul>
 *   <li>{@link #put} 走内容寻址（sha256 分层），天然去重，适合流水线产物；</li>
 *   <li>{@link #putAt} 用调用方给的 key，适合"归属于某个业务对象"的附件——
 *       患者附件如果也按内容去重，两个患者上同一份文件时会互相删掉对方的东西。</li>
 * </ul>
 */
public interface ObjectStorage {

    StoredObject put(byte[] content, String contentType, String extension);

    /** 指定 key 写入（覆盖）。用于业务归属明确、需要按前缀列举的对象。 */
    StoredObject putAt(String objectKey, byte[] content);

    byte[] get(String objectKey);

    void delete(String objectKey);

    /** 列出某前缀下的全部对象键（相对根目录、以 / 分隔）。用于孤儿扫描。 */
    List<String> listKeys(String prefix);

    boolean healthy();

    record StoredObject(String objectKey, String sha256, long sizeBytes) {
    }
}
