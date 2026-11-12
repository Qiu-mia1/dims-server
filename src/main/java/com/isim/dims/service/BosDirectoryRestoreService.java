package com.isim.dims.service;

import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.model.BosObjectSummary;
import com.baidubce.services.bos.model.GetObjectRequest;
import com.baidubce.services.bos.model.ListObjectsRequest;
import com.baidubce.services.bos.model.ListObjectsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * 百度 BOS 本地目录恢复服务
 *
 * <p>用于「本地目录已被清理、但需要重新导入」的场景（如 DataSlice 重跑 reimport）：
 * 本地目录不存在时，按「本地路径 ↔ BOS 对象 key」一一对应的既有约定
 * （见 {@code DownloadMMTDataBOSSchedule#resolveLocalPath}，即
 * {@code 本地路径 = dims.download.local-dir + "/" + 对象key}）
 * 反推出对象前缀，把该前缀下的对象原样下载回原路径，保证后续本地目录导入流水线可直接复用。</p>
 *
 * <p>安全约束：只允许恢复 {@code dims.download.local-dir} 之下的目录；
 * 对象 key 解析出的落地路径必须仍在该根目录内（防目录穿越）。</p>
 *
 * @feature: ingestion
 */
@Slf4j
@Service
public class BosDirectoryRestoreService {

    /**
     * BOS 列表分页大小
     */
    private static final int PAGE_SIZE = 1000;

    /**
     * 与下载调度保持一致的过滤标记：forever_log 文件不参与导入
     */
    private static final String FOREVER_LOG_MARKER = "forever_log";

    private final BosClient bosClient;

    /**
     * 下载桶（与 DownloadMMTDataBOSSchedule / DownloadAnotherBOSSchedule 同桶）
     */
    @Value("${minio.download-bucket:mmt_ads}")
    private String bucket;

    /**
     * 下载落地根目录：BOS key 与本地相对路径一一对应
     */
    @Value("${dims.download.local-dir:/data/downloads}")
    private String localDir;

    public BosDirectoryRestoreService(BosClient bosClient) {
        this.bosClient = bosClient;
    }

    /**
     * 校验本地目录是否存在，不存在则从 BOS 按同名前缀恢复到本地。
     *
     * @param localPath 本地目录绝对路径（如 DataSlice.importSourceDir 对应的会话目录）
     * @return 实际下载的文件数；本地目录已存在且包含文件时返回 0
     * @throws IllegalStateException 路径非法、BOS 上无对应对象、或下载失败
     */
    public int restoreIfMissing(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            throw new IllegalArgumentException("RESTORE_PATH_EMPTY: localPath is required");
        }

        Path root = Path.of(localDir).toAbsolutePath().normalize();
        Path target = Path.of(trimTrailingSeparators(localPath.trim())).toAbsolutePath().normalize();

        if (Files.isDirectory(target)) {
            if (containsAnyFile(target)) {
                log.info("本地目录已存在且包含文件，无需从 BOS 恢复: {}", target);
                return 0;
            }
            log.info("本地目录已存在但不含任何文件，继续从 BOS 恢复: {}", target);
        }

        if (!target.startsWith(root)) {
            throw new IllegalStateException(
                    "RESTORE_PATH_NOT_ALLOWED: " + target + " 不在下载根目录 " + root + " 之下，无法从 BOS 恢复");
        }

        String objectPrefix = root.relativize(target).toString().replace('\\', '/') + "/";
        List<BosObjectSummary> objects = listObjects(objectPrefix);
        if (objects.isEmpty()) {
            throw new IllegalStateException(
                    "RESTORE_OBJECT_NOT_FOUND: BOS 桶[" + bucket + "] 前缀[" + objectPrefix + "] 下无对象");
        }

        log.info("本地目录不存在，开始从 BOS 恢复: bucket={}, prefix={}, 对象数={}", bucket, objectPrefix, objects.size());

        int downloaded = 0;
        for (BosObjectSummary obj : objects) {
            String key = obj.getKey();
            // 跳过目录占位对象与不参与导入的 forever_log 文件
            if (key == null || key.endsWith("/") || key.contains(FOREVER_LOG_MARKER)) {
                continue;
            }
            Path localFile = root.resolve(Path.of("").resolve(key.startsWith("/") ? key.substring(1) : key).normalize());
            if (!localFile.startsWith(root)) {
                throw new IllegalStateException("RESTORE_PATH_NOT_ALLOWED: 非法对象 key " + key);
            }
            try {
                if (localFile.getParent() != null) {
                    Files.createDirectories(localFile.getParent());
                }
                bosClient.getObject(new GetObjectRequest(bucket, key), localFile.toFile());
                downloaded++;
                log.debug("恢复完成: {} -> {} ({} 字节)", key, localFile, obj.getSize());
            } catch (IOException e) {
                throw new IllegalStateException("RESTORE_DOWNLOAD_FAILED: " + key + " -> " + localFile, e);
            } catch (RuntimeException e) {
                throw new IllegalStateException("RESTORE_DOWNLOAD_FAILED: " + key + " -> " + localFile, e);
            }
        }

        if (downloaded == 0) {
            throw new IllegalStateException(
                    "RESTORE_OBJECT_NOT_FOUND: BOS 桶[" + bucket + "] 前缀[" + objectPrefix + "] 下无有效数据文件");
        }

        // 兜底建目录：仅含目录占位对象的场景下也保证目录存在，便于后续导入校验
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new IllegalStateException("RESTORE_DOWNLOAD_FAILED: 创建目录失败 " + target, e);
        }

        log.info("BOS 恢复完成: {} ({} 个文件)", target, downloaded);
        return downloaded;
    }

    /* ==================== 按需加载：前缀统计 + 全量拉取 ==================== */

    /**
     * BOS 前缀下的对象统计（数据切片「按需加载」的预计耗时与进度分母）
     */
    public static class PrefixStat {

        /** 前缀下有效对象（排除目录占位与 forever_log）总字节数 */
        private final long totalBytes;

        /** 前缀下有效对象个数 */
        private final int fileCount;

        /** 其中已存在于本地且大小一致（无需重新下载）的字节数 */
        private final long existingBytes;

        public PrefixStat(long totalBytes, int fileCount, long existingBytes) {
            this.totalBytes = totalBytes;
            this.fileCount = fileCount;
            this.existingBytes = existingBytes;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public int getFileCount() {
            return fileCount;
        }

        public long getExistingBytes() {
            return existingBytes;
        }

        /** 仍需下载的字节数 */
        public long getRemainingBytes() {
            return Math.max(0L, totalBytes - existingBytes);
        }
    }

    /**
     * 统计某 BOS 对象前缀下需要拉取的数据量（供「按需加载」给出预计耗时）。
     *
     * @param objectPrefix BOS 对象前缀（如 {@code {bosPrefix}/{yyyyMMdd}/{md5}/{sessionId}}，带不带尾斜杠均可）
     * @return 统计结果（前缀下无有效对象时 fileCount=0、totalBytes=0）
     * @throws IllegalStateException 前缀为空、非法或落在下载根目录之外
     */
    public PrefixStat statPrefix(String objectPrefix) {
        Path root = Path.of(localDir).toAbsolutePath().normalize();
        String prefix = normalizePrefix(objectPrefix);
        ensureInsideRoot(root, prefix);

        List<BosObjectSummary> objects = listObjects(prefix);
        long totalBytes = 0L;
        long existingBytes = 0L;
        int fileCount = 0;
        for (BosObjectSummary obj : objects) {
            String key = obj.getKey();
            if (isSkippable(key)) {
                continue;
            }
            long size = obj.getSize();
            fileCount++;
            totalBytes += size;
            Path localFile = localFileOf(root, key);
            if (localFile != null && Files.isRegularFile(localFile) && sizeOf(localFile) == size) {
                existingBytes += size;
            }
        }
        log.info("BOS 前缀统计: bucket={}, prefix={}, 对象数={}, 总字节={}, 本地已有={}",
                bucket, prefix, fileCount, totalBytes, existingBytes);
        return new PrefixStat(totalBytes, fileCount, existingBytes);
    }

    /**
     * 按 BOS 对象前缀把该会话目录下的对象原样拉取到本地（保持目录结构）。
     *
     * <p>供数据切片「按需加载」使用：meta-only 导入只落 meta + 前视鱼眼，
     * 用户点「加载」时再由本方法补齐其余文件；已存在且大小一致的文件跳过
     * （幂等，支持失败重试）。本地落地路径与 {@link #restoreIfMissing(String)} 一致：
     * {@code 本地路径 = dims.download.local-dir + "/" + 对象key}，与后续本地目录导入流水线对齐。</p>
     *
     * @param objectPrefix     BOS 对象前缀（如 {@code {bosPrefix}/{yyyyMMdd}/{md5}/{sessionId}}）
     * @param progressListener 进度回调（已下载字节，含本地已有部分；总字节），可为 null
     * @return 本次新下载的文件数
     * @throws IllegalStateException 前缀非法、前缀下无有效对象或下载失败
     */
    public int downloadPrefix(String objectPrefix, BiConsumer<Long, Long> progressListener) {
        Path root = Path.of(localDir).toAbsolutePath().normalize();
        String prefix = normalizePrefix(objectPrefix);
        Path target = ensureInsideRoot(root, prefix);

        List<BosObjectSummary> objects = listObjects(prefix);
        long totalBytes = objects.stream()
                .filter(obj -> !isSkippable(obj.getKey()))
                .mapToLong(BosObjectSummary::getSize)
                .sum();

        long doneBytes = 0L;
        int downloaded = 0;
        for (BosObjectSummary obj : objects) {
            String key = obj.getKey();
            if (isSkippable(key)) {
                continue;
            }
            Path localFile = localFileOf(root, key);
            if (localFile == null) {
                throw new IllegalStateException("LOAD_PATH_NOT_ALLOWED: 非法对象 key " + key);
            }
            // 幂等：本地已存在且大小一致 → 视为已完成（重复加载 / 失败重试不重复下载）
            if (Files.isRegularFile(localFile) && sizeOf(localFile) == obj.getSize()) {
                doneBytes += obj.getSize();
                notifyProgress(progressListener, doneBytes, totalBytes);
                continue;
            }
            try {
                if (localFile.getParent() != null) {
                    Files.createDirectories(localFile.getParent());
                }
                bosClient.getObject(new GetObjectRequest(bucket, key), localFile.toFile());
                downloaded++;
                doneBytes += obj.getSize();
                notifyProgress(progressListener, doneBytes, totalBytes);
                log.debug("按需加载下载完成: {} -> {} ({} 字节)", key, localFile, obj.getSize());
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("LOAD_DOWNLOAD_FAILED: " + key + " -> " + localFile, e);
            }
        }

        if (downloaded == 0 && totalBytes == 0) {
            throw new IllegalStateException(
                    "LOAD_OBJECT_NOT_FOUND: BOS 桶[" + bucket + "] 前缀[" + prefix + "] 下无有效数据文件");
        }
        // 兜底建目录：即使前缀下只有目录占位对象，也保证会话目录存在，便于流水线校验
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new IllegalStateException("LOAD_DOWNLOAD_FAILED: 创建目录失败 " + target, e);
        }

        log.info("按需加载 BOS 拉取完成: prefix={}, 本次新下载={} 个文件, 总字节={}", prefix, downloaded, totalBytes);
        return downloaded;
    }

    /**
     * 按前缀分页列举对象（BOS 单页上限 1000，使用 marker 翻页）。
     */
    private List<BosObjectSummary> listObjects(String prefix) {
        List<BosObjectSummary> all = new ArrayList<>();
        String marker = null;

        do {
            ListObjectsRequest request = new ListObjectsRequest(bucket);
            request.setPrefix(prefix);
            request.setMaxKeys(PAGE_SIZE);
            if (marker != null) {
                request.setMarker(marker);
            }

            ListObjectsResponse response = bosClient.listObjects(request);
            List<BosObjectSummary> contents = response.getContents();
            if (contents == null || contents.isEmpty()) {
                break;
            }
            all.addAll(contents);

            marker = null;
            if (response.isTruncated()) {
                marker = response.getNextMarker();
                if (marker == null) {
                    // 兜底：以本页最后一个 key 作为下一页起点
                    marker = contents.get(contents.size() - 1).getKey();
                }
            }
        } while (marker != null);

        return all;
    }

    /**
     * 前缀规范化：去首尾空白、去前导斜杠、补尾斜杠（BOS 前缀统一以 "/" 结尾）
     */
    private static String normalizePrefix(String objectPrefix) {
        if (objectPrefix == null || objectPrefix.isBlank()) {
            throw new IllegalStateException("LOAD_PREFIX_EMPTY: objectPrefix is required");
        }
        String prefix = objectPrefix.trim();
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /**
     * 前缀 → 本地目标目录（下载根目录下的相对路径），并校验未越出根目录
     */
    private static Path ensureInsideRoot(Path root, String prefix) {
        String dirPart = prefix.substring(0, prefix.length() - 1);
        Path target = root.resolve(Path.of("").resolve(dirPart).normalize()).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException(
                    "LOAD_PATH_NOT_ALLOWED: " + target + " 不在下载根目录 " + root + " 之下");
        }
        return target;
    }

    /**
     * 对象 key → 本地落地路径；非法（越出根目录）时返回 null
     */
    private static Path localFileOf(Path root, String key) {
        String cleanKey = key.startsWith("/") ? key.substring(1) : key;
        Path localFile = root.resolve(Path.of("").resolve(cleanKey).normalize()).normalize();
        return localFile.startsWith(root) ? localFile : null;
    }

    /**
     * 目录下（含子目录）是否存在任意文件。
     *
     * <p>用于区分「目录已恢复」与「目录被清空/仅剩空壳」两种场景：
     * 不存在任何文件时应继续从 BOS 恢复。</p>
     *
     * @param dir 本地目录
     * @return 存在至少一个普通文件返回 true；目录不存在或遍历失败视为不存在文件
     */
    private static boolean containsAnyFile(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            log.warn("遍历本地目录失败，视为不含文件并继续从 BOS 恢复: {}", dir, e);
            return false;
        }
    }

    /**
     * 是否需要跳过：目录占位对象（以 "/" 结尾）与不参与导入的 forever_log 文件
     */
    private static boolean isSkippable(String key) {
        return key == null || key.endsWith("/") || key.contains(FOREVER_LOG_MARKER);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static void notifyProgress(BiConsumer<Long, Long> listener, long doneBytes, long totalBytes) {
        if (listener != null) {
            listener.accept(doneBytes, totalBytes);
        }
    }

    private static String trimTrailingSeparators(String path) {
        String p = path;
        while (p.length() > 1 && (p.endsWith("/") || p.endsWith("\\"))) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}