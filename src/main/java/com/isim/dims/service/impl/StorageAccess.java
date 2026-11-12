package com.isim.dims.service.impl;

import com.isim.dims.config.StorageConfig;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.exception.StorageArchivedException;
import com.isim.dims.exception.StorageException;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.StorageService;
import com.isim.dims.service.StorageStat;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 存储访问门面（MinIO 迁移）——业务层唯一存储入口
 *
 * <p>封装 StorageService（local / minio 双实现）+ 归档拦截 + 本地物化，
 * 业务层禁止直接依赖 StorageService 实现类。</p>
 *
 * <h3>归档拦截（存量 19GB 不迁移）</h3>
 * <p>仅 minio 模式生效：storage_type=LOCAL 的存量记录其数据只归档不迁移，
 * 数据内容读取类操作抛 {@link StorageArchivedException}（Controller 映射 410 DATA_ARCHIVED）；
 * local 模式永不拦截。</p>
 *
 * <h3>本地物化 materializeLocal</h3>
 * <p>Python 进程只能消费本地文件：local 模式零拷贝解析为文件系统路径；
 * minio 模式下载到 tempDir/materialized 缓存（sha1(key) 命名、保留扩展名、
 * 6h TTL 失效重下、顺带清理 24h 前旧文件）。</p>
 *
 * <h3>Python 产物 staging</h3>
 * <p>minio 模式下 Python 产物先写入 tempDir/staging，成功后 commitStagedDir
 * 上传到对象存储并清理 staging；local 模式 Python 直接写最终位置（零拷贝）。</p>
 * @feature: infra
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageAccess {

    /** 物化缓存 TTL */
    private static final long CACHE_TTL_HOURS = 6;

    /** 物化缓存目录清理阈值 */
    private static final long CACHE_SWEEP_HOURS = 24;

    private final StorageService storageService;
    private final StorageConfig storageConfig;
    private final DataSliceMapper dataSliceMapper;

    public boolean isMinioMode() {
        return "minio".equalsIgnoreCase(storageConfig.getType());
    }

    // ==================== 归档拦截 ====================

    /**
     * 归档判定（只读，不抛异常）：归档 ⇔ <b>minio 模式 且 storage_type=LOCAL</b>。
     *
     * <p>给「只需判定、不需要拦截」的场景用（如单车看板据此把下载按钮置灰）。
     * 与 {@link #assertNotArchived(DataSlice)} 共用同一条件，避免两处口径各自漂移 ——
     * 注意方向：<b>LOCAL 才是归档</b>（存量本地数据不迁移 MinIO，仅归档），
     * 新导入的切片在 minio 模式下是 MINIO、可读可下载。</p>
     */
    public boolean isArchived(DataSlice slice) {
        return isMinioMode() && slice != null && "LOCAL".equalsIgnoreCase(slice.getStorageType());
    }

    /**
     * 归档拦截：仅 minio 模式下 storage_type=LOCAL 的记录抛 StorageArchivedException。
     * 元数据查询类操作不应调用本方法；需要判定而不拦截时用 {@link #isArchived(DataSlice)}。
     */
    public void assertNotArchived(DataSlice slice) {
        if (isArchived(slice)) {
            throw new StorageArchivedException("slice", slice.getId());
        }
    }

    /**
     * 解析切片「原始产物」的存储引用：
     * <ul>
     *   <li>minio 模式：归档拦截 + file_path（入库时写入的存储 key，可能是
     *       original.bag / original.zip / original.tar.gz），file_path 不是合法 key 时回退规范 key
     *       slices/{id}/original.bag</li>
     *   <li>local 模式：file_path（兼容存量绝对路径 / 相对 key），空则回退规范 key</li>
     * </ul>
     */
    public String resolveBagKey(DataSlice slice) {
        if (slice == null) {
            throw new StorageException("SLICE_NOT_FOUND: slice is null");
        }
        if (isMinioMode()) {
            assertNotArchived(slice);
            String fp = slice.getFilePath();
            return isStorageKey(fp) ? fp : StorageKeys.originalBag(slice.getId());
        }
        String fp = slice.getFilePath();
        return (fp == null || fp.isBlank()) ? StorageKeys.originalBag(slice.getId()) : fp;
    }

    /** 判断是否为对象存储 key（非空、非绝对路径、非 URI），用于排除存量本地绝对路径。 */
    private static boolean isStorageKey(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        String value = filePath.trim();
        return !value.startsWith("/") && !value.startsWith("\\")
                && !value.contains(":\\") && !value.contains(":/");
    }

    /**
     * 匿名/无 DB 上下文端点（如 /data/videos/**）解析可读 key：
     * minio 模式下先查 DB 做归档拦截，local 模式直接放行。
     */
    public String resolveReadKey(String sliceId, String key) {
        if (isMinioMode()) {
            assertNotArchived(dataSliceMapper.selectById(sliceId));
        }
        return key;
    }

    // ==================== 存储委托 ====================

    public boolean exists(String key) {
        return storageService.exists(key);
    }

    public StorageStat stat(String key) {
        return storageService.stat(key);
    }

    public InputStream open(String key) {
        return storageService.open(key);
    }

    public InputStream open(String key, long start, long end) {
        return storageService.open(key, start, end);
    }

    public String readString(String key) {
        try (InputStream in = storageService.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("READ_FAILED: " + key, e);
        }
    }

    public void put(String key, InputStream in, long size, String contentType) {
        storageService.put(key, in, size, contentType);
    }

    public void put(String key, Path localFile, String contentType) {
        storageService.put(key, localFile, contentType);
    }

    public void put(String key, byte[] data, String contentType) {
        storageService.put(key, new ByteArrayInputStream(data), data.length, contentType);
    }

    public void delete(String key) {
        storageService.delete(key);
    }

    public void deleteByPrefix(String prefix) {
        storageService.deleteByPrefix(prefix);
    }

    public List<String> list(String prefix) {
        return storageService.list(prefix);
    }

    /** local 模式：key → 本地路径；minio 模式：仅用于 staging 计算，抛 UnsupportedOperationException */
    public Path resolveLocalPath(String key) {
        return storageService.resolveLocalPath(key);
    }

    // ==================== 本地物化 ====================

    /**
     * 将存储引用物化为本地文件路径（Python 进程输入）：
     * local 模式零拷贝；minio 模式下载到 tempDir/materialized 缓存。
     */
    public Path materializeLocal(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException("STORAGE_INVALID_KEY: key must not be blank");
        }
        if (!isMinioMode()) {
            return resolveLocalPath(key);
        }
        Path cached = materializeCacheDir().resolve(cacheFileName(key));
        try {
            Files.createDirectories(cached.getParent());
            boolean stale = !Files.exists(cached)
                    || Files.size(cached) == 0
                    || isOlderThan(cached, CACHE_TTL_HOURS);
            if (stale) {
                // 原子写入（Review W4）：先下载到 .part 临时文件，成功后 ATOMIC_MOVE
                // 覆盖目标 —— 消除并发半写文件被 Python 读取 / 崩溃残留脏文件的问题
                Path part = cached.resolveSibling(cached.getFileName() + ".part");
                Files.deleteIfExists(part); // 清理崩溃残留
                try {
                    storageService.download(key, part);
                    Files.move(part, cached, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.deleteIfExists(part); // 下载/移动失败 → 清理 .part
                    throw e;
                }
            }
            sweepMaterializeCache();
            return cached;
        } catch (IOException e) {
            throw new StorageException("READ_FAILED: materialize " + key, e);
        }
    }

    private Path materializeCacheDir() {
        return Paths.get(storageConfig.getTempDir(), "materialized");
    }

    private String cacheFileName(String key) {
        return sha1Hex(key) + extensionOf(key);
    }

    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        int slash = key.lastIndexOf('/');
        if (dot > slash && dot < key.length() - 1) {
            return key.substring(dot);
        }
        return "";
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static boolean isOlderThan(Path file, long hours) throws IOException {
        Instant lastModified = Files.getLastModifiedTime(file).toInstant();
        return lastModified.isBefore(Instant.now().minus(hours, ChronoUnit.HOURS));
    }

    /** 顺带清理过期的物化缓存文件（每次 materializeLocal 触发一次扫描） */
    private void sweepMaterializeCache() {
        Path dir = materializeCacheDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return isOlderThan(p, CACHE_SWEEP_HOURS);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.debug("materialize cache sweep failed: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.debug("materialize cache sweep aborted: {}", e.getMessage());
        }
    }

    // ==================== Python 产物 staging ====================

    /** Python 产物 staging 根目录（minio 模式；local 模式直接写最终位置） */
    public Path stageDir(String sub) {
        return Paths.get(storageConfig.getTempDir(), "staging", sub);
    }

    /**
     * 将 staging 目录全部文件上传到 keyPrefix 前缀下，成功后清理 staging。
     * local 模式为 no-op（Python 已直接写最终位置）。
     */
    public void commitStagedDir(Path stageRoot, String keyPrefix) {
        if (!isMinioMode()) {
            return;
        }
        if (!Files.isDirectory(stageRoot)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(stageRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String rel = stageRoot.relativize(file).toString().replace(File.separatorChar, '/');
                String key = keyPrefix + rel;
                storageService.put(key, file, contentTypeOf(rel));
                log.debug("Staged product uploaded: {}", key);
            }
            deleteRecursively(stageRoot);
        } catch (IOException e) {
            throw new StorageException("UPLOAD_FAILED: commit staged dir " + stageRoot, e);
        }
    }

    private static String contentTypeOf(String rel) {
        if (rel.endsWith(".json")) {
            return "application/json";
        }
        if (rel.endsWith(".jsonl")) {
            return "application/x-ndjson";
        }
        if (rel.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (rel.endsWith(".bag")) {
            return "application/octet-stream";
        }
        return "application/octet-stream";
    }

    /** CFDI/Docker 链路本地工作目录（local: cfdiDir/{sliceId}；minio: tempDir/cfdi/{sliceId}） */
    public Path cfdiWorkDir(String sliceId) {
        return isMinioMode()
                ? Paths.get(storageConfig.getTempDir(), "cfdi", sliceId)
                : Paths.get(storageConfig.getCfdiDir(), sliceId);
    }

    public void cleanupCfdiWorkDir(String sliceId) {
        Path dir = cfdiWorkDir(sliceId);
        if (Files.isDirectory(dir)) {
            deleteRecursively(dir);
        }
    }

    /** 递归删除目录（含自身） */
    public void deleteRecursively(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.debug("delete failed: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("walk failed for {}", root, e);
        }
    }

    /**
     * 预留给未来直传场景：生成对象预签名下载 URL。
     * 当前下载统一走后端代理，不实现。
     */
    public String presignedGetUrl(String key) {
        throw new UnsupportedOperationException("presignedGetUrl not implemented; downloads go through backend proxy");
    }
}
