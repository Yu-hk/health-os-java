package com.kangheng.healthos.provider.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.config.HealthOsProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.kangheng.healthos.spi.storage.ObjectStorage;

/**
 * 本地文件系统对象存储。
 *
 * <p>写入路径按 {@code 前2位/前4位/sha256.ext} 分层，天然去重；
 * 先写临时文件再原子改名，避免读到半个文件。
 */
@Component
@ConditionalOnProperty(name = "healthos.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectStorage.class);

    private final Path root;

    public LocalObjectStorage(HealthOsProperties properties) {
        this.root = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new UncheckedIOException("无法创建对象存储根目录: " + root, ex);
        }
        log.info("本地对象存储根目录: {}", root);
    }

    @Override
    public StoredObject put(byte[] content, String contentType, String extension) {
        String sha256 = Ids.sha256Hex(content);
        String suffix = extension == null || extension.isBlank() ? ".bin" : "." + extension;
        String objectKey = sha256.substring(0, 2) + "/" + sha256.substring(0, 4) + "/" + sha256 + suffix;
        Path target = root.resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return new StoredObject(objectKey, sha256, content.length);
        } catch (IOException ex) {
            throw new UncheckedIOException("写入对象失败: " + objectKey, ex);
        }
    }

    @Override
    public StoredObject putAt(String objectKey, byte[] content) {
        Path target = resolveSafely(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new StoredObject(objectKey, Ids.sha256Hex(content), content.length);
        } catch (IOException ex) {
            throw new UncheckedIOException("写入对象失败: " + objectKey, ex);
        }
    }

    @Override
    public List<String> listKeys(String prefix) {
        String normalized = prefix == null ? "" : prefix.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        Path base = normalized.isEmpty() ? root : resolveSafely(normalized);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(base)) {
            Path rootPath = root;
            return walk.filter(Files::isRegularFile)
                    .map(path -> rootPath.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("列举对象失败: " + prefix, ex);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        Path target = resolveSafely(objectKey);
        try {
            if (!Files.exists(target)) {
                throw new IllegalStateException("对象不存在: " + objectKey);
            }
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new UncheckedIOException("读取对象失败: " + objectKey, ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolveSafely(objectKey));
        } catch (IOException ex) {
            throw new UncheckedIOException("删除对象失败: " + objectKey, ex);
        }
    }

    @Override
    public boolean healthy() {
        return Files.isDirectory(root) && Files.isWritable(root);
    }

    /** 防目录穿越：解析后必须仍在根目录下。 */
    private Path resolveSafely(String objectKey) {
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法对象键: " + objectKey);
        }
        return target;
    }
}
