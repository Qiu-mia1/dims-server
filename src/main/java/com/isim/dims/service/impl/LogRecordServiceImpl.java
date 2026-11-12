package com.isim.dims.service.impl;

import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.model.BosObjectSummary;
import com.baidubce.services.bos.model.ListObjectsRequest;
import com.baidubce.services.bos.model.ListObjectsResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.LogRecordListResponse;
import com.isim.dims.entity.LogRecord;
import com.isim.dims.entity.VinMd5Mapping;
import com.isim.dims.mapper.LogRecordMapper;
import com.isim.dims.mapper.VinMd5MappingMapper;
import com.isim.dims.service.LogRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 日志包记录服务实现
 *
 * <p>对象布局（mmt-ads 为例）：</p>
 * <pre>
 *   {bosPrefix}/{yyyyMMdd}/{vin_md5}/{sessionDir}/meta                     ← 会话级 meta
 *   {bosPrefix}/{yyyyMMdd}/{vin_md5}/{sessionDir}/public/{us}_forever_log  ← 独立日志文件（forever_log / bsw_log）
 * </pre>
 *
 * <p>处理策略：</p>
 * <ol>
 *   <li>逐个 {@code _log} 文件登记一条 log_record，meta 由文件所在目录逐级向上查找；</li>
 *   <li>meta 缺失/解析失败时不影响登记，仅元数据字段为空（保留 key/目录/大小等目录信息）；</li>
 *   <li>重复扫描按 bos_key 幂等 upsert，保证调度可重试。</li>
 * </ol>
 *
 * @feature: ingestion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogRecordServiceImpl implements LogRecordService {

    /** 单次打包下载的最大对象数，避免误打包超大目录 */
    private static final int MAX_BUNDLE_FILES = 5000;

    /** BOS 单页上限 */
    private static final int PAGE_SIZE = 1000;

    /** 文件名前缀形如 1787449444614889_ 的微秒时间戳 */
    private static final Pattern US_TS_PREFIX = Pattern.compile("^(\\d{16,17})_.*$");

    private static final String META_FILE_NAME = "meta";

    private static final String LOG_MARKER = "_log";

    /** 仅登记这两类日志，其余 _log 文件（如 maf_log 等）不入库 */
    private static final Set<String> ALLOWED_LOG_TYPES = Set.of("forever_log", "bsw_log");

    /** 32 位 hex：区分 meta.vin 存的是 VIN 摘要还是明文 VIN */
    private static final Pattern MD5_HEX = Pattern.compile("^[0-9a-fA-F]{32}$");

    /** 子集目录之外最多向上查找 meta 的层数（会话目录一般就在上一级） */
    private static final int MAX_META_LOOKUP_ABOVE_SUBDIR = 3;

    private final LogRecordMapper logRecordMapper;

    private final ObjectMapper objectMapper;

    private final BosClient bosClient;

    private final VinMd5MappingMapper vinMd5MappingMapper;

    @Value("${dims.download.local-dir: /data/downloads}")
    private String localDir;

    @Value("${minio.download-bucket:mmt_ads}")
    private String bucket;

    @Value("${dims.download.bos-prefix:mmt_test}")
    private String bosPrefix;

    /* ==================== 元数据登记 ==================== */

    @Override
    public int recordLogObjects(String bucket, String subDir, Collection<BosObjectSummary> objects) {
        if (objects == null || objects.isEmpty()) {
            return 0;
        }
        Map<String, BosObjectSummary> byKey = new LinkedHashMap<>();
        // 按日志文件所在目录分组：同一目录只登记一条记录
        Map<String, List<BosObjectSummary>> logFilesByDir = new LinkedHashMap<>();
        // 目录内全部对象（含 meta 等非 `_log` 文件），用于统计目录文件数与总大小
        Map<String, List<BosObjectSummary>> allByDir = new LinkedHashMap<>();
        for (BosObjectSummary obj : objects) {
            if (obj == null || obj.getKey() == null) {
                continue;
            }
            String key = obj.getKey();
            byKey.put(key, obj);
            String dir = parentOf(key);
            if (dir == null || key.endsWith("/")) {
                continue;
            }
            allByDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(obj);
            // 规则按存储目录路径判断（而非文件名）：仅 forever_log / bsw_log 目录登记
            if (ALLOWED_LOG_TYPES.stream().anyMatch(t -> dir.contains(t))) {
                logFilesByDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(obj);
            }
        }
        if (logFilesByDir.isEmpty()) {
            return 0;
        }

        // 同一子集目录下多个日志文件共用同一份 meta，读一次即可
        Map<String, String> metaCache = new HashMap<>();
        // BOS 存在性探测结果缓存，避免每个日志目录都重复 list
        Map<String, Boolean> existsCache = new HashMap<>();
        int saved = 0;
        for (Map.Entry<String, List<BosObjectSummary>> entry : logFilesByDir.entrySet()) {
            String dirPrefix = entry.getKey();
            try {
                // 代表文件：目录内 key 字典序最小的日志文件
                BosObjectSummary representative = entry.getValue().stream()
                        .min(Comparator.comparing(BosObjectSummary::getKey))
                        .orElse(null);
                if (representative == null) {
                    continue;
                }
                List<BosObjectSummary> dirObjects = allByDir.getOrDefault(dirPrefix, List.of());
                String metaKey = locateMetaKey(bucket, byKey, dirPrefix, subDir, existsCache);
                String metaContent = readText(bucket, metaKey, metaCache);
                Map<String, Object> meta = parseMeta(metaContent);
                upsert(buildRecord(bucket, representative, dirObjects, dirPrefix, metaKey, metaContent, meta));
                saved++;
            } catch (Exception e) {
                // 日志登记失败不能影响 meta-only 主流程
                log.warn("日志记录登记失败: dir={}", dirPrefix, e);
            }
        }
        if (saved > 0) {
            log.info("meta-only 日志记录已登记：子集目录[{}] {} 个日志目录 / 对象 {} 个", subDir, saved, objects.size());
        }
        return saved;
    }

    /**
     * 从日志文件所在目录逐级向上查找同名 {@code meta} 对象。
     *
     * <p>会话目录（{@code {day}/{vin}/{session}_public}）通常位于子集目录的上一级，
     * 因此先在本批对象集合内查找，找不到再向子集目录之外最多探测 {@value #MAX_META_LOOKUP_ABOVE_SUBDIR} 级。</p>
     */
    private String locateMetaKey(String bucket, Map<String, BosObjectSummary> byKey, String dirPrefix,
                                 String subDir, Map<String, Boolean> existsCache) {
        String dir = dirPrefix;
        boolean beyondSubDir = false;
        int beyond = 0;
        while (dir != null && !dir.isEmpty()) {
            String candidate = dir + "/" + META_FILE_NAME;
            if (byKey.containsKey(candidate) || bosExists(bucket, candidate, existsCache)) {
                return candidate;
            }
            if (subDir != null && dir.equals(subDir)) {
                beyondSubDir = true;
            }
            if (beyondSubDir && ++beyond > MAX_META_LOOKUP_ABOVE_SUBDIR) {
                break;
            }
            dir = parentOf(dir);
        }
        return null;
    }

    /**
     * 探测 BOS 上是否存在指定对象（用 listObjects + 精确 key 比对，避免多一次 HEAD 语义差异）。
     */
    private boolean bosExists(String bucket, String key, Map<String, Boolean> cache) {
        return cache.computeIfAbsent(key, k -> {
            try {
                ListObjectsRequest request = new ListObjectsRequest(bucket);
                request.setPrefix(k);
                request.setMaxKeys(1);
                List<BosObjectSummary> contents = bosClient.listObjects(request).getContents();
                return contents != null && !contents.isEmpty() && k.equals(contents.get(0).getKey());
            } catch (Exception e) {
                log.debug("探测 meta 对象失败: key={} err={}", k, e.getMessage());
                return false;
            }
        });
    }

    /**
     * 读取 meta 文本：优先本地已下载文件，其次从 BOS 流式读取。
     */
    private String readText(String bucket, String key, Map<String, String> cache) throws IOException {
        if (key == null) {
            return null;
        }
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        String content = null;
        Path localFile = resolveLocalPath(key);
        if (Files.isRegularFile(localFile)) {
            content = Files.readString(localFile, StandardCharsets.UTF_8);
        } else {
            try (InputStream in = bosClient.getObject(bucket, key).getObjectContent()) {
                if (in != null) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("读取 meta 失败: key={} err={}", key, e.getMessage());
            }
        }
        cache.put(key, content);
        return content;
    }

    /**
     * 解析 meta：JSON 优先，解析失败降级为 {@code key=value} 逐行解析。
     */
    private Map<String, Object> parseMeta(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{")) {
            try {
                return objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("meta JSON 解析失败，降级为 key=value 解析: {}", e.getMessage());
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (String line : trimmed.split("\\r?\\n")) {
            int idx = line.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return map;
    }

    private LogRecord buildRecord(String bucket, BosObjectSummary obj, List<BosObjectSummary> dirObjects,
                                  String dirPrefix, String metaKey, String metaContent,
                                  Map<String, Object> meta) {
        String key = obj.getKey();
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        String logType = resolveLogType(dirPrefix);

        LogRecord record = new LogRecord();
        record.setLogType(logType);
        record.setFileName(fileName);
        record.setBosKey(key);
        record.setDirPrefix(dirPrefix);
        record.setBucket(bucket);
        // 目录维度汇总：与「整个目录打包下载」的内容保持一致
        record.setFileCount((int) dirObjects.stream().filter(o -> !o.getKey().endsWith("/")).count());
        record.setFileSize(dirObjects.stream()
                .filter(o -> !o.getKey().endsWith("/"))
                .mapToLong(o -> Math.max(o.getSize(), 0L))
                .sum());
        record.setLastModified(dirObjects.stream()
                .map(BosObjectSummary::getLastModified)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(date -> LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()))
                .orElse(null));
        record.setLogTime(resolveLogTime(fileName));

        record.setCarType(str(meta, "car_type"));
        record.setSubCarType(str(meta, "sub_car_type"));
        record.setCollectType(str(meta, "collect_type"));
        record.setCountry(str(meta, "country"));
        record.setDataSourceEnv(str(meta, "data_source_env"));
        record.setDumpBeginUs(num(meta, "dump_begin_us"));
        String eventName = str(meta, "event_name");
        record.setEventName(eventName == null || eventName.isBlank() ? logType : eventName);
        record.setFdiType(str(meta, "fdi_type"));
        record.setIsSoca(bool(meta, "is_soca"));
        record.setMafInterface(str(meta, "maf_interface"));
        record.setPriority(str(meta, "priority"));
        record.setProject(str(meta, "project"));
        record.setSystemName(str(meta, "system_name"));
        record.setUseAnonymousId(bool(meta, "use_anonymous_id"));
        record.setUtcDiffUs(num(meta, "utc_diff_us"));
        record.setUuid(str(meta, "uuid"));
        record.setVin(resolveVin(str(meta, "vin")));

        record.setMetaKey(metaKey);
        record.setMetaJson(metaContent);
        return record;
    }

    /**
     * 日志类型：按存储目录路径判断（而非文件名）。目录末级形如
     * {@code 1789520027000_forever_log_1699}，从中取 forever_log / bsw_log 标识。
     */
    private String resolveLogType(String path) {
        String lastSeg = fileNameOf(path);
        String rest = lastSeg.replaceFirst("^\\d+_", "");
        int idx = rest.indexOf('_');
        String candidate = idx > 0 ? rest.substring(0, idx) : rest;
        return ALLOWED_LOG_TYPES.contains(candidate) ? candidate : "log";
    }

    /**
     * 日志时间：文件名前缀微秒时间戳（UTC）换算为本地时间，解析失败返回 null。
     */
    private LocalDateTime resolveLogTime(String fileName) {
        var matcher = US_TS_PREFIX.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            long micros = Long.parseLong(matcher.group(1));
            // 合理区间：2001-09-09 ~ 2286-11-20，避免把其他数字前缀误当时间戳
            if (micros < 1_000_000_000_000_000L || micros > 10_000_000_000_000_000L) {
                return null;
            }
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(micros / 1_000_000L,
                    (micros % 1_000_000L) * 1000L), ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按 dir_prefix 幂等 upsert（一行 = 一个日志目录）：已存在则更新元数据，保留原 id/created_at。
     */
    private void upsert(LogRecord record) {
        LogRecord existing = logRecordMapper.selectOne(new LambdaQueryWrapper<LogRecord>()
                .eq(LogRecord::getDirPrefix, record.getDirPrefix())
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            logRecordMapper.insert(record);
            return;
        }
        record.setId(existing.getId());
        record.setCreatedAt(existing.getCreatedAt() == null ? now : existing.getCreatedAt());
        record.setUpdatedAt(now);
        logRecordMapper.updateById(record);
    }

    /* ==================== 历史回填 ==================== */

    @Override
    public int rebuild(String date) {
        String prefix = bosPrefix == null ? "" : bosPrefix.trim();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (date != null && !date.isBlank()) {
            prefix = prefix.isEmpty() ? date.trim() : prefix + "/" + date.trim();
        }
        String listPrefix = prefix.isEmpty() ? "" : prefix + "/";
        List<BosObjectSummary> all = listObjects(bucket, listPrefix);
        if (all.isEmpty()) {
            log.info("日志记录重建：前缀[{}]下无对象", listPrefix);
            return 0;
        }

        // 按会话目录分组（日志文件所在目录的上一级），同组共用一份 meta
        Map<String, List<BosObjectSummary>> groups = new LinkedHashMap<>();
        for (BosObjectSummary obj : all) {
            String key = obj.getKey();
            if (key == null || key.endsWith("/") || !key.contains(LOG_MARKER)) {
                continue;
            }
            String dir = parentOf(key);
            String groupKey = dir == null ? listPrefix : parentOf(dir) == null ? dir : parentOf(dir);
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>());
        }

        int total = 0;
        for (String groupKey : groups.keySet()) {
            String groupPrefix = groupKey + "/";
            List<BosObjectSummary> siblings = all.stream()
                    .filter(o -> o.getKey() != null && o.getKey().startsWith(groupPrefix))
                    .toList();
            total += recordLogObjects(bucket, groupKey, siblings);
        }
        log.info("日志记录重建完成：前缀[{}] 分组 {} 个，登记 {} 条", listPrefix, groups.size(), total);
        return total;
    }

    /* ==================== 列表查询 ==================== */

    @Override
    public LogRecordListResponse list(String vin, String logType, String eventName, String collectType,
                                      String project, String carType, String keyword,
                                      int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);

        LambdaQueryWrapper<LogRecord> query = new LambdaQueryWrapper<LogRecord>()
                .like(hasText(vin), LogRecord::getVin, vin)
                .eq(hasText(logType), LogRecord::getLogType, logType)
                .eq(hasText(eventName), LogRecord::getEventName, eventName)
                .eq(hasText(collectType), LogRecord::getCollectType, collectType)
                .eq(hasText(project), LogRecord::getProject, project)
                .eq(hasText(carType), LogRecord::getCarType, carType);
        if (hasText(keyword)) {
            String kw = keyword.trim();
            query.and(w -> w.like(LogRecord::getFileName, kw)
                    .or().like(LogRecord::getVin, kw)
                    .or().like(LogRecord::getSystemName, kw)
                    .or().like(LogRecord::getBosKey, kw));
        }

        long total = logRecordMapper.selectCount(query);

        query.orderByDesc(LogRecord::getLogTime).orderByDesc(LogRecord::getLastModified);
        query.last("LIMIT " + safePageSize + " OFFSET " + (long) (safePage - 1) * safePageSize);
        List<LogRecord> items = logRecordMapper.selectList(query);

        LogRecordListResponse response = new LogRecordListResponse();
        response.setItems(items);
        response.setTotal(total);
        response.setPage(safePage);
        response.setPageSize(safePageSize);
        return response;
    }

    @Override
    public LogRecord getById(String id) {
        return id == null || id.isBlank() ? null : logRecordMapper.selectById(id);
    }

    /* ==================== 目录打包下载 ==================== */

    @Override
    public void writeBundle(String id, OutputStream out) throws IOException {
        LogRecord record = getById(id);
        if (record == null) {
            throw new IllegalArgumentException("LOG_RECORD_NOT_FOUND: " + id);
        }
        String bucket = record.getBucket();
        String dirPrefix = record.getDirPrefix() == null ? "" : record.getDirPrefix();
        String prefix = dirPrefix.isEmpty() ? "" : dirPrefix + "/";

        List<String> keys = new ArrayList<>();
        for (BosObjectSummary obj : listObjects(bucket, prefix)) {
            String key = obj.getKey();
            if (key == null || key.endsWith("/")) {
                continue;
            }
            keys.add(key);
            if (keys.size() >= MAX_BUNDLE_FILES) {
                log.warn("目录对象数超过上限 {}，仅打包前 {} 个: {}", MAX_BUNDLE_FILES, MAX_BUNDLE_FILES, prefix);
                break;
            }
        }
        if (keys.isEmpty() && record.getBosKey() != null) {
            keys.add(record.getBosKey());
        }
        log.info("开始打包日志目录: id={} prefix={} 对象数={}", id, prefix, keys.size());

        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String key : keys) {
                String entryName = key.startsWith(prefix) ? key.substring(prefix.length()) : key;
                if (entryName.isEmpty()) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(entryName));
                try {
                    copyObject(bucket, key, zos);
                } catch (Exception e) {
                    log.warn("打包日志文件失败，跳过: key={} err={}", key, e.getMessage());
                }
                zos.closeEntry();
            }
            zos.finish();
        }
        log.info("日志目录打包完成: id={} prefix={}", id, prefix);
    }

    @Override
    public String bundleFileName(LogRecord record) {
        String vin = record.getVin() == null || record.getVin().isBlank() ? "unknown" : record.getVin();
        String logType = record.getLogType() == null || record.getLogType().isBlank() ? "log" : record.getLogType();
        LocalDateTime time = record.getLogTime() != null ? record.getLogTime() : record.getLastModified();
        String suffix = time == null ? "" : "_" + time.toLocalDate().toString().replace("-", "");
        return logType + "_" + vin + suffix + ".zip";
    }

    /**
     * 写入单个对象：优先本地文件，缺失时从 BOS 流式读取。
     */
    private void copyObject(String bucket, String key, OutputStream out) throws IOException {
        Path localFile = resolveLocalPath(key);
        if (Files.isRegularFile(localFile)) {
            Files.copy(localFile, out);
            return;
        }
        try (InputStream in = bosClient.getObject(bucket, key).getObjectContent()) {
            if (in == null) {
                return;
            }
            in.transferTo(out);
        }
    }

    /**
     * 按前缀分页列举对象（marker 翻页）。
     */
    private List<BosObjectSummary> listObjects(String bucket, String prefix) {
        List<BosObjectSummary> all = new ArrayList<>();
        String marker = null;
        do {
            ListObjectsRequest request = new ListObjectsRequest(bucket);
            if (prefix != null && !prefix.isEmpty()) {
                request.setPrefix(prefix);
            }
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
            if (Boolean.TRUE.equals(response.isTruncated())) {
                marker = response.getNextMarker();
                if (marker == null || marker.isEmpty()) {
                    BosObjectSummary last = contents.get(contents.size() - 1);
                    marker = last.getKey();
                }
            }
        } while (marker != null && !marker.isEmpty());
        return all;
    }

    /* ==================== 工具方法 ==================== */

    private Path resolveLocalPath(String key) {
        String relative = key.startsWith("/") ? key.substring(1) : key;
        return Path.of(localDir).resolve(relative).normalize();
    }

    private static String parentOf(String key) {
        int idx = key.lastIndexOf('/');
        return idx > 0 ? key.substring(0, idx) : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String str(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long num(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean bool(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 从 BOS key 取文件名（末级）。
     */
    private static String fileNameOf(String key) {
        int idx = key.lastIndexOf('/');
        return idx >= 0 ? key.substring(idx + 1) : key;
    }

    /**
     * 解析落库 VIN：meta.vin 实测为 32 位 md5，需经 {@code vin_md5_mapping} 反查明文 VIN 再落库；
     * 已是明文 VIN 则原样返回；映射未命中/查询失败时降级保留 meta 原值（md5）。
     */
    private String resolveVin(String metaVin) {
        String raw = blankToNull(metaVin);
        if (raw == null) {
            return null;
        }
        if (MD5_HEX.matcher(raw).matches()) {
            try {
                VinMd5Mapping mapping = vinMd5MappingMapper.selectOne(
                        new LambdaQueryWrapper<VinMd5Mapping>()
                                .eq(VinMd5Mapping::getMd5, raw)
                                .last("LIMIT 1"));
                if (mapping != null && blankToNull(mapping.getVin()) != null) {
                    return mapping.getVin().trim();
                }
                log.warn("[LOG] vin_md5_mapping 未命中摘要 vin={}，保留 MD5 原值落库", raw);
            } catch (Exception e) {
                log.warn("[LOG] vin_md5_mapping 查询失败，保留原值 vin={} err={}", raw, e.getMessage());
            }
        }
        return raw;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
