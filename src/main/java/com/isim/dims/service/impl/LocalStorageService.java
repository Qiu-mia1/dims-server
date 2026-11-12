package com.isim.dims.service.impl;

import com.isim.dims.config.StorageConfig;
import com.isim.dims.exception.StorageException;
import com.isim.dims.exception.StorageNotFoundException;
import com.isim.dims.service.StorageService;
import com.isim.dims.service.StorageStat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件系统存储实现（MinIO 迁移，默认模式）
 *
 * <p>key → StorageConfig 目录映射（key 首个路径段决定根目录）：</p>
 * <pre>
 *   slices/      → bagDir          trajectories/ → bagDir
 *   exports/     → exportDir       replay/       → replayCacheDir
 *   videos/      → videoDir        scene_data/   → bagDir/scene_data
 *   signals/     → signalDir       cockpit/      → bagDir/cockpit
 *   cfdi/        → cfdiDir         tasks/        → bagDir/.tasks
 * </pre>
 *
 * <p>兼容性：存量 DB 中 file_path 为绝对路径（以 "/" 开头）时直接按绝对路径处理；
 * 绝对路径与相对 key 均须通过归一化包含性校验（必须位于配置的任一数据根目录内）；
 * 读取路径另有 toRealPath 真实路径包含性校验（symlink 逃逸防御）。</p>
 *
 * <p>目录语义：deleteByPrefix 删除前缀指向目录的<b>全部内容并连带删除目录本身</b>
 * （保持旧切片删除行为：切片目录一并清除）；与 minio 模式（对象存储无目录概念，
 * 仅删除前缀匹配的 object key）语义等价——两种模式都保证前缀下数据被完整清除。</p>
 * @feature: infra
 */
@Slf4j
@Component
@ConditionalOnExpression(
        "'${dims.storage.type:local}' == 'local' || '${dims.storage.type:local}' == 'bos'")
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private final StorageConfig storageConfig;

    /** key 首个路径段 → 根目录 */
    private Path baseDirOf(String prefix) {
        switch (prefix) {
            case "slices":       return Paths.get(storageConfig.getBagDir());
            case "exports":      return Paths.get(storageConfig.getExportDir());
            case "videos":       return Paths.get(storageConfig.getVideoDir());
            case "signals":      return Paths.get(storageConfig.getSignalDir());
            case "trajectories": return Paths.get(storageConfig.getBagDir());
            case "replay":       return Paths.get(storageConfig.getReplayCacheDir());
            case "scene_data":   return Paths.get(storageConfig.getBagDir(), "scene_data");
            case "cockpit":      return Paths.get(storageConfig.getBagDir(), "cockpit");
            case "tasks":        return Paths.get(storageConfig.getBagDir(), ".tasks");
            case "cfdi":         return Paths.get(storageConfig.getCfdiDir());
            default:
                throw new StorageException("STORAGE_INVALID_KEY: unknown key prefix '" + prefix + "'");
        }
    }

    /**
     * 全部数据根目录（归一化绝对形式）：绝对路径 key 必须位于其中任一目录内
     * （Review S1 第二层防线——存量绝对路径 file_path 均在这些目录内，不会误伤）。
     */
    private List<Path> dataRoots() {
        return Stream.of(
                        storageConfig.getBagDir(),
                        storageConfig.getExportDir(),
                        storageConfig.getVideoDir(),
                        storageConfig.getSignalDir(),
                        storageConfig.getReplayCacheDir(),
                        storageConfig.getCfdiDir())
                .filter(p -> p != null && !p.isBlank())
                .map(p -> Paths.get(p).toAbsolutePath().normalize())
                .toList();
    }

    /** 归一化绝对路径是否位于任一数据根目录内 */
    private boolean isWithinDataRoots(Path normalizedAbs) {
        for (Path root : dataRoots()) {
            if (normalizedAbs.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Path resolveLocalPath(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException("STORAGE_INVALID_KEY: key must not be blank");
        }
        if (key.startsWith("/")) {
            // 存量绝对路径 file_path：归一化后必须位于任一数据根目录内
            // （Review S1：防止 "../.." 归一化逃逸读到根目录外任意文件）
            Path absolute = Paths.get(key).toAbsolutePath().normalize();
            if (!isWithinDataRoots(absolute)) {
                throw new StorageException("STORAGE_INVALID_KEY: path escapes storage root: " + key);
            }
            return absolute;
        }
        if (key.endsWith("/")) {
            // 目录语义：key 是前缀（list/deleteByPrefix 用）
            return resolvePrefix(key);
        }
        int slash = key.indexOf('/');
        if (slash <= 0) {
            throw new StorageException("STORAGE_INVALID_KEY: key must contain a prefix: " + key);
        }
        String prefix = key.substring(0, slash);
        Path base = baseDirOf(prefix);
        Path resolved = base.resolve(key.substring(slash + 1)).normalize();
        if (!resolved.startsWith(base)) {
            throw new StorageException("STORAGE_INVALID_KEY: key escapes storage root: " + key);
        }
        return resolved;
    }

    /** 前缀 → 其指向的目录（list / deleteByPrefix 使用） */
    private Path resolvePrefix(String prefix) {
        if (prefix.startsWith("/")) {
            Path absolute = Paths.get(prefix).toAbsolutePath().normalize();
            if (!isWithinDataRoots(absolute)) {
                throw new StorageException("STORAGE_INVALID_KEY: prefix escapes storage root: " + prefix);
            }
            return absolute;
        }
        int slash = prefix.indexOf('/');
        String head = slash > 0 ? prefix.substring(0, slash) : prefix;
        Path base = baseDirOf(head);
        if (slash <= 0) {
            return base;
        }
        Path target = base.resolve(prefix.substring(slash + 1)).normalize();
        if (!target.startsWith(base)) {
            throw new StorageException("STORAGE_INVALID_KEY: prefix escapes storage root: " + prefix);
        }
        return target;
    }

    /**
     * 解析并校验 key 指向的常规文件（含 symlink 逃逸防御）。
     * 相对 key：真实路径必须仍位于对应根目录内（toRealPath 包含性校验）；
     * 绝对 key：归一化包含性校验（resolveLocalPath 内）+ 真实路径必须位于
     * 任一数据根目录内（Review S1：symlink 无法把读操作带出根目录）。
     * 文件不存在 / 逃逸 → StorageNotFoundException。
     */
    private Path fileFor(String key) {
        Path p = resolveLocalPath(key);
        if (!key.startsWith("/")) {
            int slash = key.indexOf('/');
            Path base = baseDirOf(key.substring(0, slash));
            try {
                Path baseReal = base.toRealPath();
                Path real = p.toRealPath();
                if (!real.startsWith(baseReal)) {
                    log.warn("Storage key rejected (path escapes root): key={}, real={}", key, real);
                    throw new StorageNotFoundException(key);
                }
                return real;
            } catch (IOException e) {
                throw new StorageNotFoundException(key);
            }
        }
        if (!Files.isRegularFile(p)) {
            throw new StorageNotFoundException(key);
        }
        try {
            Path real = p.toRealPath();
            for (Path root : dataRoots()) {
                if (real.startsWith(root.toRealPath())) {
                    return real;
                }
            }
            log.warn("Storage key rejected (path escapes root): key={}, real={}", key, real);
            throw new StorageNotFoundException(key);
        } catch (IOException e) {
            throw new StorageNotFoundException(key);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return Files.isRegularFile(resolveLocalPath(key));
        } catch (StorageException e) {
            return false;
        }
    }

    @Override
    public StorageStat stat(String key) {
        Path p = fileFor(key);
        try {
            return new StorageStat(Files.size(p), null);
        } catch (IOException e) {
            throw new StorageException("READ_FAILED: " + key, e);
        }
    }

    @Override
    public void put(String key, InputStream in, long size, String contentType) {
        Path p = resolveLocalPath(key);
        try {
            Files.createDirectories(p.getParent());
            try (InputStream source = in; OutputStream out = Files.newOutputStream(p)) {
                source.transferTo(out);
            }
        } catch (IOException e) {
            throw new StorageException("UPLOAD_FAILED: " + key, e);
        }
    }

    @Override
    public void put(String key, Path localFile, String contentType) {
        Path p = resolveLocalPath(key);
        try {
            Files.createDirectories(p.getParent());
            Files.move(localFile, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("UPLOAD_FAILED: " + key + " from " + localFile, e);
        }
    }

    @Override
    public void download(String key, Path destFile) {
        Path p = fileFor(key);
        try {
            Files.createDirectories(destFile.getParent());
            Files.copy(p, destFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("READ_FAILED: " + key, e);
        }
    }

    @Override
    public InputStream open(String key) {
        return open(key, 0, Long.MAX_VALUE);
    }

    @Override
    public InputStream open(String key, long start, long end) {
        Path p = fileFor(key);
        try {
            InputStream in = Files.newInputStream(p);
            if (start > 0) {
                in.skipNBytes(start);
            }
            long length = end == Long.MAX_VALUE ? Long.MAX_VALUE : (end - start + 1);
            return new RangeInputStream(in, length);
        } catch (IOException e) {
            throw new StorageException("READ_FAILED: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path p = resolveLocalPath(key);
        try {
            if (Files.isDirectory(p)) {
                // 兼容存量 file_path 指向目录（LOCAL 导入转换前）→ 不删
                log.debug("Storage delete skipped (is directory): {}", p);
                return;
            }
            Files.deleteIfExists(p);
            // 顺带清理空父目录（保持旧切片删除语义：切片目录一并清除）
            Path parent = p.getParent();
            if (parent != null) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException e) {
            throw new StorageException("DELETE_FAILED: " + key, e);
        }
    }

    /**
     * 删除前缀指向目录下的全部内容，并连带删除目录本身（保持旧切片删除语义）。
     * 与 minio 模式（对象存储无目录概念，仅删除前缀匹配的 object key）语义等价：
     * 两种模式都保证前缀下数据被完整清除；差异仅在 local 模式删除后目录实体不存在。
     */
    @Override
    public void deleteByPrefix(String prefix) {
        Path target = resolvePrefix(prefix);
        if (!Files.isDirectory(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(target))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            // 内容清空后顺带删除空目录本身（保持旧切片删除语义：目录一并清除）
            Files.deleteIfExists(target);
        } catch (IOException | UncheckedIOException e) {
            throw new StorageException("DELETE_FAILED: prefix " + prefix, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        Path target = resolvePrefix(prefix);
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(target)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> prefix + target.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new StorageException("READ_FAILED: list prefix " + prefix, e);
        }
    }

    /**
     * 限长读取流：最多读取 remaining 字节后返回 EOF（Range 请求语义）
     */
    private static final class RangeInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        RangeInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = delegate.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = delegate.read(b, off, toRead);
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
