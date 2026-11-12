//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package com.isim.dims.schedule;

import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.model.BosObjectSummary;
import com.baidubce.services.bos.model.GetObjectRequest;
import com.baidubce.services.bos.model.ListObjectsRequest;
import com.baidubce.services.bos.model.ListObjectsResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.service.MxfImportService;
import com.isim.dims.service.impl.StorageAccess;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class DownloadAnotherBOSSchedule {
    private static final Logger log = LoggerFactory.getLogger(DownloadAnotherBOSSchedule.class);
    @Value("${minio.download-bucket: mmt_ads}")
    private String bucket;
    @Value("${dims.download.another-bos-prefix:Momenta-MXF/LUS_CX835ICA_sop}")
    private String anotherBosPrefix;
    @Value("${dims.download.another-local-dir:/data/downloads}")
    private String anotherLocalDir;
    @Value("${dims.download.another-record-file:}")
    private String anotherRecordFile;
    @Value("${dims.download.another-recent-hours:1440}")
    private long anotherRecentHours;
    @Value("${dims.download.another-upload-lag-minutes:30}")
    private long anotherUploadLagMinutes;
    @Value("${dims.download.another-day-format:yyyy-MM-dd}")
    private String anotherDayFormat;
    @Value("${dims.download.another-bos.cleanup-downloaded:true}")
    private boolean cleanupDownloaded;
    private AtomicBoolean running = new AtomicBoolean(false);
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;
    private final MxfImportService importService;
    private final StorageAccess storageAccess;
    @Autowired
    private BosClient bosClient;

    @Scheduled(cron = "0 0/1 * * * ? ")
    public void downloadDataBySubdir() {
        if (this.anotherBosPrefix != null && !this.anotherBosPrefix.isBlank()) {
            if (!this.running.compareAndSet(false, true)) {
                log.info("上一次另前缀下载任务仍在执行，本次调度跳过");
            } else {
                String lockKey = "lock:downloadAnotherBOSDataBySubdir";
                RLock lock = this.redissonClient.getLock(lockKey);
                boolean locked = false;

                try {
                    locked = lock.tryLock(10L, 60L, TimeUnit.SECONDS);
                    if (locked) {
                        LocalDate today = LocalDate.now();
                        List<String> processingPrefixes = this.recentDayPrefixes(today);
                        log.info("扫描 BOS 桶[{}] 另前缀[{}] 近 {} 小时内日目录 {} 个: {}", new Object[]{this.bucket, this.anotherBosPrefix, this.anotherRecentHours, processingPrefixes.size(), processingPrefixes});
                        Map<String, Set<BosObjectSummary>> subdirs = new LinkedHashMap();

                        for (String dayPrefix : processingPrefixes) {
                            subdirs.putAll(this.groupBySubdir(dayPrefix));
                        }

                        if (subdirs.isEmpty()) {
                            log.info("另前缀[{}] 处理窗口内无对象，无下载", this.anotherBosPrefix);
                            return;
                        }

                        log.info("另前缀扫描到子集目录 {} 个: {}", subdirs.size(), subdirs.keySet());
                        Path recordPath = this.anotherRecordFilePath();
                        Set<String> downloadedDirs = this.loadDownloadedDirs(recordPath);
                        log.debug("另前缀 downloadedDirs: {}", downloadedDirs);
                        int beforeClean = downloadedDirs.size();
                        if (downloadedDirs.removeIf((d) -> processingPrefixes.stream().noneMatch((p) -> d.startsWith(p)))) {
                            log.info("另前缀已清理 {} 条非窗口内旧记录，仅保留 {} 条子集目录记录", beforeClean - downloadedDirs.size(), downloadedDirs.size());
                            this.saveDownloadedDirs(recordPath, downloadedDirs);
                        }

                        int processed = 0;

                        for (Map.Entry<String, Set<BosObjectSummary>> e : subdirs.entrySet()) {
                            String subDir = (String) e.getKey();
                            if (downloadedDirs.contains(subDir)) {
                                log.info("另前缀子集目录已下载，跳过: {}", subDir);
                            } else {
                                List<File> downloadedFiles;
                                try {
                                    downloadedFiles = this.downloadSubdir(subDir, (Set) e.getValue());
                                } catch (Exception ex) {
                                    log.error("另前缀子集目录下载失败，本次不记录，下次将重试: {}", subDir, ex);
                                    continue;
                                }

                                try {
                                    this.importService.importPackets(this.toMultipartFiles(downloadedFiles), "MMT");
                                } catch (Exception ex) {
                                    log.error("另前缀子集目录导入失败，本次不记录，下次将重试: {}", subDir, ex);
                                    continue;
                                }

                                if (cleanupDownloaded) {
                                    Path localSubDir = Path.of(this.anotherLocalDir).resolve(subDir);
                                    this.storageAccess.deleteRecursively(localSubDir);
                                }

                                downloadedDirs.add(subDir);
                                this.saveDownloadedDirs(recordPath, downloadedDirs);
                                ++processed;
                                log.info("另前缀子集目录下载完成并已记录: {} ({} 个文件)", subDir, downloadedFiles.size());
                            }
                        }

                        log.info("本次新下载另前缀子集目录 {} 个", processed);
                        return;
                    }

                    log.warn("downloadAnotherBOSDataBySubdir 获取分布式锁失败");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("downloadAnotherBOSDataBySubdir 获取分布式锁被中断", e);
                } catch (Exception e) {
                    log.error("另前缀按子集目录下载异常：{}", e);
                    return;
                } finally {
                    if (locked && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }

                    this.running.set(false);
                }

            }
        } else {
            log.warn("anotherBosPrefix 未配置(dims.download.another-bos-prefix)，本次调度跳过");
        }
    }

    private Map<String, Set<BosObjectSummary>> groupBySubdir(String dayPrefix) {
        Map<String, Set<BosObjectSummary>> map = new LinkedHashMap();
        String marker = null;

        do {
            ListObjectsRequest request = new ListObjectsRequest(this.bucket);
            request.setMaxKeys(1000);
            request.setPrefix(dayPrefix);
            if (marker != null) {
                request.setMarker(marker);
            }

            ListObjectsResponse response = this.bosClient.listObjects(request);

            for (BosObjectSummary obj : response.getContents()) {
                String key = obj.getKey();
                if (!key.endsWith("/") && !key.contains("forever_log")) {
                    if (!this.isMature(obj)) {
                        log.debug("另前缀对象上传未满 {} 分钟，本次跳过: {}", this.anotherUploadLagMinutes, key);
                    } else {
                        String subDir = this.resolveSubdir(key, dayPrefix);
                        if (subDir != null) {
                            ((Set) map.computeIfAbsent(subDir, (k) -> new LinkedHashSet())).add(obj);
                        }
                    }
                }
            }

            marker = response.isTruncated() ? response.getNextMarker() : null;
        } while (marker != null);

        return map;
    }

    private boolean isMature(BosObjectSummary obj) {
        if (this.anotherUploadLagMinutes > 0L && obj.getLastModified() != null) {
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(this.anotherUploadLagMinutes));
            return obj.getLastModified().toInstant().isBefore(cutoff);
        } else {
            return true;
        }
    }

    private String buildDayPrefix(LocalDate day) {
        String dayStr = day.format(DateTimeFormatter.ofPattern(this.anotherDayFormat));
        return this.anotherBosPrefix != null && !this.anotherBosPrefix.isBlank() ? this.anotherBosPrefix + "/" + dayStr + "/" : dayStr + "/";
    }

    private List<String> recentDayPrefixes(LocalDate today) {
        List<String> prefixes = new ArrayList();
        long daysBack = this.anotherRecentHours <= 0L ? 0L : (this.anotherRecentHours + 23L) / 24L;

        for (long i = daysBack; i >= 0L; --i) {
            prefixes.add(this.buildDayPrefix(today.minusDays(i)));
        }

        return prefixes;
    }

    private String resolveSubdir(String key, String dayPrefix) {
        if (!key.startsWith(dayPrefix)) {
            return null;
        } else {
            String rel = key.substring(dayPrefix.length());
            int slash = rel.indexOf(47);
            if (slash <= 0) {
                return dayPrefix.endsWith("/") ? dayPrefix.substring(0, dayPrefix.length() - 1) : dayPrefix;
            } else {
                String folder = rel.substring(0, slash);
                return dayPrefix + folder;
            }
        }
    }

    private List<File> downloadSubdir(String subDir, Set<BosObjectSummary> objects) throws IOException {
        List<File> downloadedFiles = new ArrayList();

        for (BosObjectSummary obj : objects) {
            log.debug("下载的另前缀子集目录是：{}", subDir);
            String key = obj.getKey();
            if (!key.contains("forever_log")) {
                Path localFile = this.resolveLocalPath(key);
                Files.createDirectories(localFile.getParent());
                GetObjectRequest getRequest = new GetObjectRequest(this.bucket, key);
                this.bosClient.getObject(getRequest, localFile.toFile());
                File file = localFile.toFile();
                log.info("下载完成: {} -> {} ({} 字节)", new Object[]{key, localFile, obj.getSize()});
                if (key.endsWith(".tar.gz")) {
                    downloadedFiles.add(file);
                }
            }
        }

        return downloadedFiles;
    }

    private MultipartFile[] toMultipartFiles(List<File> files) {
        return (MultipartFile[]) files.stream().map(LocalFileMultipartFile::new).toArray((x$0) -> new MultipartFile[x$0]);
    }

    private Path resolveLocalPath(String key) {
        String cleanKey = key.startsWith("/") ? key.substring(1) : key;
        Path localDirPath = Path.of(this.anotherLocalDir);
        Path relative = Path.of("").resolve(cleanKey).normalize();
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("非法对象 key: " + key);
        } else {
            return localDirPath.resolve(relative);
        }
    }

    private Set<String> loadDownloadedDirs(Path file) {
        if (!Files.exists(file, new LinkOption[0])) {
            return new LinkedHashSet();
        } else {
            try {
                List<String> list = (List) this.objectMapper.readValue(file.toFile(), new TypeReference<List<String>>() {
                });
                return new LinkedHashSet(list);
            } catch (IOException e) {
                log.warn("读取另前缀子集目录记录失败，按空记录处理: {}", file, e);
                return new LinkedHashSet();
            }
        }
    }

    private void saveDownloadedDirs(Path file, Collection<String> dirs) {
        try {
            Files.createDirectories(file.getParent());
            List<String> ordered = new ArrayList(dirs);
            Collections.sort(ordered);
            this.objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), ordered);
            log.info("已更新另前缀子集目录下载记录: {} (共 {} 个)", file, ordered.size());
        } catch (IOException e) {
            log.error("写入另前缀子集目录下载记录失败", e);
        }

    }

    private Path anotherRecordFilePath() {
        return this.anotherRecordFile != null && !this.anotherRecordFile.isBlank() ? Path.of(this.anotherRecordFile) : Path.of(this.anotherLocalDir).resolve("download-momenta-mxf-subdirs.json");
    }

    public DownloadAnotherBOSSchedule(final ObjectMapper objectMapper, final RedissonClient redissonClient, final MxfImportService importService, final StorageAccess storageAccess) {
        this.objectMapper = objectMapper;
        this.redissonClient = redissonClient;
        this.importService = importService;
        this.storageAccess = storageAccess;
    }

    private static final class LocalFileMultipartFile implements MultipartFile {
        private final File file;

        LocalFileMultipartFile(File file) {
            this.file = file;
        }

        public String getName() {
            return this.file.getName();
        }

        public String getOriginalFilename() {
            return this.file.getName();
        }

        public String getContentType() {
            return "application/gzip";
        }

        public boolean isEmpty() {
            return this.file.length() == 0L;
        }

        public long getSize() {
            return this.file.length();
        }

        public byte[] getBytes() throws IOException {
            try (InputStream in = new FileInputStream(this.file)) {
                return in.readAllBytes();
            }
        }

        public InputStream getInputStream() throws IOException {
            return new FileInputStream(this.file);
        }

        public void transferTo(File dest) throws IOException, IllegalStateException {
            Files.copy(this.file.toPath(), dest.toPath());
        }
    }
}
