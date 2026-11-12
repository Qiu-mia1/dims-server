//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package com.isim.dims.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.service.MxfImportService;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

@Component
public class DownloadAnotherS3Schedule {
    private static final Logger log = LoggerFactory.getLogger(DownloadAnotherS3Schedule.class);
    private String bucket = "test";
    @Value("${dims.download.another-bos-prefix:Momenta-MXF/LUS_CX835ICA_dev}")
    private String anotherBosPrefix;
    @Value("${dims.download.another-local-dir:/data/downloads}")
    private String anotherLocalDir;
    @Value("${dims.download.another-s3-record-file:}")
    private String anotherRecordFile;
    @Value("${dims.download.another-recent-hours:48}")
    private long anotherRecentHours;
    @Value("${dims.download.another-upload-lag-minutes:30}")
    private long anotherUploadLagMinutes;
    @Value("${dims.download.another-day-format:yyyy-MM-dd}")
    private String anotherDayFormat;
    private AtomicBoolean running = new AtomicBoolean(false);
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;
    private final MxfImportService importService;

//    @Scheduled(cron = "0 0/1 * * * ? ")
    public void downloadDataBySubdir() {
        if (this.anotherBosPrefix != null && !this.anotherBosPrefix.isBlank()) {
            if (!this.running.compareAndSet(false, true)) {
                log.info("上一次另前缀(S3)下载任务仍在执行，本次调度跳过");
            } else {
                String lockKey = "lock:downloadAnotherS3DataBySubdir";
                RLock lock = this.redissonClient.getLock(lockKey);
                boolean locked = false;

                try {
                    locked = lock.tryLock(10L, 60L, TimeUnit.SECONDS);
                    if (locked) {
                        LocalDate today = LocalDate.now();
                        List<String> processingPrefixes = this.recentDayPrefixes(today);
                        log.info("扫描 S3 桶[{}] 另前缀[{}] 近 {} 小时内日目录 {} 个: {}", new Object[]{this.bucket, this.anotherBosPrefix, this.anotherRecentHours, processingPrefixes.size(), processingPrefixes});
                        Map<String, Set<S3Object>> subdirs = new LinkedHashMap();

                        for (String dayPrefix : processingPrefixes) {
                            subdirs.putAll(this.groupBySubdir(dayPrefix));
                        }

                        if (subdirs.isEmpty()) {
                            log.info("另前缀(S3)[{}] 处理窗口内无对象，无下载", this.anotherBosPrefix);
                            return;
                        }

                        log.info("另前缀(S3)扫描到子集目录 {} 个: {}", subdirs.size(), subdirs.keySet());
                        Path recordPath = this.anotherRecordFilePath();
                        Set<String> downloadedDirs = this.loadDownloadedDirs(recordPath);
                        log.debug("另前缀(S3) downloadedDirs: {}", downloadedDirs);
                        int beforeClean = downloadedDirs.size();
                        if (downloadedDirs.removeIf((d) -> processingPrefixes.stream().noneMatch((p) -> d.startsWith(p)))) {
                            log.info("另前缀(S3)已清理 {} 条非窗口内旧记录，仅保留 {} 条子集目录记录", beforeClean - downloadedDirs.size(), downloadedDirs.size());
                            this.saveDownloadedDirs(recordPath, downloadedDirs);
                        }

                        int processed = 0;

                        for (Map.Entry<String, Set<S3Object>> e : subdirs.entrySet()) {
                            String subDir = (String) e.getKey();
                            if (downloadedDirs.contains(subDir)) {
                                log.info("另前缀(S3)子集目录已下载，跳过: {}", subDir);
                            } else {
                                List<File> downloadedFiles;
                                try {
                                    downloadedFiles = this.downloadSubdir(subDir, (Set) e.getValue());
                                } catch (Exception ex) {
                                    log.error("另前缀(S3)子集目录下载失败，本次不记录，下次将重试: {}", subDir, ex);
                                    continue;
                                }

                                try {
                                    this.importService.importPackets(this.toMultipartFiles(downloadedFiles), "MMT");
                                } catch (Exception ex) {
                                    log.error("另前缀(S3)子集目录导入失败，本次不记录，下次将重试: {}", subDir, ex);
                                    continue;
                                }

                                downloadedDirs.add(subDir);
                                this.saveDownloadedDirs(recordPath, downloadedDirs);
                                ++processed;
                                log.info("另前缀(S3)子集目录下载完成并已记录: {} ({} 个文件)", subDir, downloadedFiles.size());
                            }
                        }

                        log.info("本次新下载另前缀(S3)子集目录 {} 个", processed);
                        return;
                    }

                    log.warn("downloadAnotherS3DataBySubdir 获取分布式锁失败");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("downloadAnotherS3DataBySubdir 获取分布式锁被中断", e);
                } catch (Exception e) {
                    log.error("另前缀(S3)按子集目录下载异常：{}", e);
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

    private Map<String, Set<S3Object>> groupBySubdir(String dayPrefix) {
        Map<String, Set<S3Object>> map = new LinkedHashMap();
        String continuationToken = null;

        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(this.bucket).maxKeys(1000).prefix(dayPrefix).continuationToken(continuationToken);
            ListObjectsV2Response response = this.s3Client.listObjectsV2((ListObjectsV2Request) request.build());

            for (S3Object obj : response.contents()) {
                String key = obj.key();
                if (!key.endsWith("/")) {
                    if (!this.isMature(obj)) {
                        log.debug("另前缀(S3)对象上传未满 {} 分钟，本次跳过: {}", this.anotherUploadLagMinutes, key);
                    } else {
                        String subDir = this.resolveSubdir(key, dayPrefix);
                        if (subDir != null) {
                            ((Set) map.computeIfAbsent(subDir, (k) -> new LinkedHashSet())).add(obj);
                        }
                    }
                }
            }

            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);

        return map;
    }

    private boolean isMature(S3Object obj) {
        if (this.anotherUploadLagMinutes > 0L && obj.lastModified() != null) {
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(this.anotherUploadLagMinutes));
            return obj.lastModified().isBefore(cutoff);
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

    private List<File> downloadSubdir(String subDir, Set<S3Object> objects) throws IOException {
        List<File> downloadedFiles = new ArrayList();

        for (S3Object obj : objects) {
            log.debug("下载的另前缀(S3)子集目录是：{}", subDir);
            String key = obj.key();
            Path localFile = this.resolveLocalPath(key);
            Files.createDirectories(localFile.getParent());
            GetObjectRequest getRequest = (GetObjectRequest) GetObjectRequest.builder().bucket(this.bucket).key(key).build();
            this.s3Client.getObject(getRequest, localFile);
            File file = localFile.toFile();
            log.info("下载完成: {} -> {} ({} 字节)", new Object[]{key, localFile, obj.size()});
            if (key.endsWith(".tar.gz")) {
                downloadedFiles.add(file);
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
                log.warn("读取另前缀(S3)子集目录记录失败，按空记录处理: {}", file, e);
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
            log.info("已更新另前缀(S3)子集目录下载记录: {} (共 {} 个)", file, ordered.size());
        } catch (IOException e) {
            log.error("写入另前缀(S3)子集目录下载记录失败", e);
        }

    }

    private Path anotherRecordFilePath() {
        return this.anotherRecordFile != null && !this.anotherRecordFile.isBlank() ? Path.of(this.anotherRecordFile) : Path.of(this.anotherLocalDir).resolve("download-another-s3-subdirs.json");
    }

    public DownloadAnotherS3Schedule(final S3Client s3Client, final ObjectMapper objectMapper, final RedissonClient redissonClient, final MxfImportService importService) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.redissonClient = redissonClient;
        this.importService = importService;
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
