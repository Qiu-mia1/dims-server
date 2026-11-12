package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.isim.dims.dto.MxfImportResponse;
import com.isim.dims.dto.MxfImportResultItem;
import com.isim.dims.entity.MxfPacket;
import com.isim.dims.entity.MxfTrajectoryPoint;
import com.isim.dims.entity.VinMd5Mapping;
import com.isim.dims.mapper.MxfPacketMapper;
import com.isim.dims.mapper.MxfTrajectoryPointMapper;
import com.isim.dims.mapper.VinMd5MappingMapper;
import com.isim.dims.service.MxfImportService;
import com.isim.dims.util.MxfSpecialEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MXF 埋点数据包导入实现。
 *
 * <h3>压缩包结构</h3>
 * <pre>
 * exp_data_packet_&lt;VIN_md5后8&gt;_&lt;usTimestamp&gt;.tar.gz
 *   ├── meta            ← UTF-8：key=value 或 JSON
 *   └── *.data          ← UTF-8 行分隔日志：[ts]:[SECTION]:{...json...}
 * </pre>
 *
 * <h3>存储策略</h3>
 * <ul>
 *   <li><b>不保存原始日志</b>：导入时在内存中一次性解析完即丢弃 .data 原始字节，
 *       不写对象存储 —— 看板、轨迹与明细查询全部读聚合结果，
 *       原始日志只在解析瞬间被使用，留存无消费方。</li>
 *   <li>不再逐行写 {@code mxf_event_record}（该表已删除）。</li>
 *   <li>导入时在内存一次性聚合：section 计数、AEB/APA/AVP 触发数、
 *       里程/时长（RUNNING-INFO 的 auto_dis/auto_duration 最大值）、
 *       最高车速口径（RUNNING-INFO 的 dis_in_last_5s_(m) 包内最大值），
 *       以 JSONB 快照落 {@code mxf_packet.section_stats}，看板直接汇总。</li>
 *   <li>行驶轨迹点解析后落 {@code mxf_trajectory_point}
 *       （包级轨迹 + 触发事件轨迹），轨迹监控页直接读表。</li>
 *   <li>基于 MD5 唯一约束去重：同一压缩包重复上传直接跳过。</li>
 *   <li>trigger_time = (trigger_timestamp_us − utc_diff_us)；缺失时回退
 *       meta.trigger_time（毫秒 epoch）。</li>
 *   <li>存量 {@code mxf/packets/**} 历史对象可用 {@link #purgeRawLogs(int)} 清理。</li>
 * </ul>
 *
 * @feature: mxf
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MxfImportServiceImpl implements MxfImportService {

    /**
     * AEB 计数口径：这 4 个 section 的记录都计入 AEB 触发次数
     */
    private static final Set<String> AEB_SECTIONS = Set.of(
            "AEB-ACTIVATE", "AEB-PB-ACTIVATE", "AEB-LIM-ACTIVATE", "AEB-EH-ACTIVATE");

    private static final String APA_SECTION = "V2-APA-PARKINGOUT-INFO";
    private static final String AVP_SECTION = "V2-LPNPIN-CRUISING-INFO";
    private static final String RUNNING_SECTION = "RUNNING-INFO";
    private static final String DISTANCE_FIELD = "auto_dis_since_power_on_(m)";
    private static final String DURATION_FIELD = "auto_duration_since_power_on_(s)";
    /** RUNNING-INFO：过去 5s 行驶里程（m），包内最大值用于换算车辆最高车速 */
    private static final String DIS_IN_LAST_5S_FIELD = "dis_in_last_5s_(m)";

    /**
     * 智驾运营看板 KPI：AUTO-INFO 里程/时长（逐行累加）
     */
    private static final String AUTO_SECTION = "AUTO-INFO";
    private static final String AUTO_DIS_IN_LAST_FIELD = "auto_dis_in_last_duration_(m)";
    private static final String AUTO_DUR_IN_LAST_FIELD = "auto_duration_(s)";

    /**
     * 智驾运营看板 KPI：最长智驾距离（单次）——包内取最大值
     */
    private static final String HNP_SECTION = "HNP-INFO";
    private static final String HNP_DIS_IN_LAST_FIELD = "hnp_dis_in_last_duration_(m)";
    private static final String UNP_SECTION = "UNP-INFO";
    private static final String UNP_DIS_IN_LAST_FIELD = "unp_dis_in_last_duration_(m)";
    private static final String RNP_SECTION = "V2-RNP-INFO";
    private static final String RNP_DIS_FIELD = "rnp_dis_m";

    /**
     * 泊车功能聚合：SECTION 名 → {功能键, 结束状态字段, 泊车时长字段, 揉库次数字段（空 = 无）}。
     *
     * <p>泊车使用次数口径：xxx-INFO 的 xxx_end_status 字段每出现一行记一次；
     * 成功率 = 结束状态为 2 的占比；接管次数 = type 为 manual 的行数。
     * 注意 AVP 表（V2-AVP-PARKINGIN-INFO）的字段名沿用 apa_* 前缀。</p>
     */
    private static final Map<String, String[]> PARKING_SECTIONS = Map.of(
            "APA-INFO", new String[]{"APA", "apa_end_status", "apa_parking_duration_(s)", "gear_switch_times"},
            "V2-RPA-PARKINGIN-INFO", new String[]{"RPA", "rpa_end_status", "rpa_parking_duration_(s)", "gear_switch_times"},
            "V2-AVP-PARKINGIN-INFO", new String[]{"AVP", "apa_end_status", "apa_parking_duration_(s)", "gear_switch_times"},
            "V2-LPNPIN-CRUISING-INFO", new String[]{"LPNP", "parking_status", "parking_duration_s", "gear_switch_times"},
            "V2-NSP-INFO", new String[]{"NSP", "nsp_end_status", "nsp_duration_(s)", ""},
            "V2-RADS-INFO", new String[]{"LRAS", "rads_end_status", "rads_duration_(s)", ""});

    /**
     * 行驶轨迹点字段（MXF 定义：加偏经纬度，"经度,纬度"，经度在前）。
     * <ul>
     *   <li>{@code pg}      —— 本行位置点（包级轨迹点，构成完整轨迹线）</li>
     *   <li>{@code enter_pg} —— 事件进入点</li>
     *   <li>{@code exit_pg}  —— 事件退出点</li>
     *   <li>{@code cur_pg}   —— 事件当前点</li>
     * </ul>
     * 值形态兼容三种：{@code "116.39,39.90"} 字符串、{@code [lon,lat]} 数组、
     * {@code {lon,lat}} 对象（lon/lng/longitude、lat/latitude 均可）。
     */
    private static final Map<String, String> PG_FIELDS = Map.of(
            "pg", "pg",
            "enter_pg", "enter",
            "exit_pg", "exit",
            "cur_pg", "cur");

    /**
     * 单包轨迹点上限（防止超大日志撑爆内存/写入耗时）
     */
    private static final int MAX_TRAJ_POINTS = 20000;

    /**
     * 轨迹点批量插入的分批大小
     */
    private static final int TRAJ_INSERT_BATCH = 500;

    /**
     * meta 文件名（兼容无后缀 / .meta / .txt）
     */
    private static final Pattern META_FILE = Pattern.compile("(?i)^(meta|metadata|info)(\\.[^.]+)?$");

    /**
     * meta 行：key=value（忽略行首 BOM、空行、注释 #）
     */
    private static final Pattern META_KV = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");

    /**
     * 日志行：[ts]:[SECTION]:{json}，ts 可包含纳秒
     */
    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\[([^\\]]+)]:\\[([^\\]]+)]:(.*)$");

    /**
     * 32 位 hex：区分 meta.vin 存的是 VIN 摘要还是明文 VIN
     */
    private static final Pattern MD5_HEX = Pattern.compile("^[0-9a-fA-F]{32}$");

    private final MxfPacketMapper packetMapper;
    private final MxfTrajectoryPointMapper trajectoryPointMapper;
    private final VinMd5MappingMapper vinMd5MappingMapper;
    private final StorageAccess storageAccess;
    private final ObjectMapper objectMapper;

    @Override
    public MxfImportResponse importPackets(MultipartFile[] files, String source) {
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);
        long t0 = System.currentTimeMillis();

        List<MxfImportResultItem> items = new ArrayList<>();
        int success = 0, skipped = 0, failed = 0;
        long totalRecords = 0;

        if (files == null || files.length == 0) {
            return MxfImportResponse.builder()
                    .source(source)
                    .totalFiles(0)
                    .successCount(0)
                    .skippedCount(0)
                    .failedCount(0)
                    .totalRecords(0L)
                    .costMs(0L)
                    .startedAt(startedAt)
                    .finishedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .items(items)
                    .build();
        }

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                MxfImportResultItem item = importSingle(file, source);
                items.add(item);
                if (Boolean.TRUE.equals(item.getSkipped())) {
                    skipped++;
                } else if (Boolean.TRUE.equals(item.getSuccess())) {
                    success++;
                    if (item.getRecordCount() != null) {
                        totalRecords += item.getRecordCount();
                    }
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("[MXF] import failed for file={}", file.getOriginalFilename(), e);
                failed++;
                items.add(MxfImportResultItem.builder()
                        .fileName(file.getOriginalFilename())
                        .success(false)
                        .skipped(false)
                        .status("FAILED")
                        .errorMsg(e.getMessage() == null ? e.toString() : e.getMessage())
                        .build());
            }
        }

        long costMs = System.currentTimeMillis() - t0;
        return MxfImportResponse.builder()
                .source(source)
                .totalFiles(files.length)
                .successCount(success)
                .skippedCount(skipped)
                .failedCount(failed)
                .totalRecords(totalRecords)
                .costMs(costMs)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now(ZoneOffset.UTC))
                .items(items)
                .build();
    }

    // ============================================================
    // 单文件导入
    // ============================================================
    public MxfImportResultItem importSingle(MultipartFile file, String source) throws IOException {
        String fileName = file.getOriginalFilename();
        byte[] bytes = file.getBytes();
        String md5 = md5Hex(bytes);
        long fileSize = bytes.length;

        // 1. 重复检测
        QueryWrapper<MxfPacket> dup = new QueryWrapper<MxfPacket>().eq("file_md5", md5);
        Long existing = packetMapper.selectCount(dup);
        if (existing != null && existing > 0) {
            MxfPacket prev = packetMapper.selectOne(dup);
            log.info("[MXF] skip duplicate file={} md5={}", fileName, md5);
            return MxfImportResultItem.builder()
                    .fileName(fileName)
                    .fileMd5(md5)
                    .fileSize(fileSize)
                    .success(true)
                    .skipped(true)
                    .packetId(prev == null ? null : prev.getId())
                    .status("DUPLICATE")
                    .recordCount(prev == null ? null : prev.getRecordCount())
                    .sectionCount(prev == null ? null : prev.getSectionCount())
                    .build();
        }

        // 2. 解压 gzip + tar
        Map<String, byte[]> entries;
        try (InputStream gz = new GzipCompressorInputStream(new BufferedInputStream(new ByteArrayInputStream(bytes)));
             TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
            entries = readTarEntries(tar);
        }

        // 3. 解析 meta
        byte[] metaBytes = findMeta(entries);
        Map<String, String> meta = parseMeta(metaBytes);

        // meta 里没有 event_name 字段（实测 0911 版 meta 只有 vin / car_type /
        // sub_car_type / trigger_time / begin_time / end_time / data_size / compress_type 等），
        // 因此事件类型一律由 .data 日志行的 SECTION 名识别，见 MxfSpecialEvents。
        // meta.vin 实测为 VIN 的 32 位 md5，需经 vin_md5_mapping 换成明文 VIN 落库
        ResolvedVin resolvedVin = resolveVin(meta.get("vin"), meta.get("vin_md5"));
        String vin = resolvedVin.vin();
        String vinMd5 = resolvedVin.md5();
        String carType = meta.get("car_type");
        String project = meta.get("project");
        String collectType = meta.get("collect_type");
        Long triggerUs = parseLongOrNull(meta.get("trigger_timestamp_us"));
        Long utcDiffUs = parseLongOrNull(meta.get("utc_diff_us"));

        // 4. 找到 .data 文件（任意第一个非 meta 的可读文件）
        byte[] dataBytes = findData(entries);

        // 5. 一次性聚合解析日志（不再逐行入库），同时按 SECTION 名识别 8 类特殊事件
        DataAggregate agg = aggregateData(dataBytes);
        // 主事件 = 包内记录数最多的那个特殊事件；无特殊事件时为 null
        String eventName = agg.primaryEventName();
        int specialEventCount = agg.specialEventTotal();

        // 6. 计算 trigger_time / packet_start / packet_end
        //    优先 trigger_timestamp_us（微秒）；缺失时回退 meta.trigger_time（毫秒 epoch）
        LocalDateTime triggerTime = toUtcLocalDateTime(triggerUs, utcDiffUs);
        if (triggerTime == null) {
            Long triggerMs = parseLongOrNull(meta.get("trigger_time"));
            if (triggerMs != null) {
                triggerTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(triggerMs), ZoneOffset.UTC);
            }
        }
        if (triggerTime == null && agg.firstTs != null) {
            triggerTime = agg.firstTs;
        }

        // 7. 组装聚合快照 JSON（section 计数 + KPI 口径统计）
        ObjectNode stats = objectMapper.createObjectNode();
        ObjectNode sections = stats.putObject("sections");
        agg.sectionCounts.forEach(sections::put);
        // 8 类特殊事件 → 记录数（无特殊事件时为空对象）
        ObjectNode events = stats.putObject("events");
        agg.specialEventCounts.forEach(events::put);
        stats.put("aebCount", agg.aebCount);
        stats.put("apaCount", agg.apaCount);
        stats.put("avpCount", agg.avpCount);
        stats.put("distanceKm", round2(agg.maxDistanceM / 1000.0));
        stats.put("durationHours", round4(agg.maxDurationS / 3600.0));
        // 车辆最高车速口径：RUNNING-INFO 过去 5s 里程包内最大值（m），查询时 /5s*3.6 换算 km/h
        stats.put("maxDisInLast5sM", round2(agg.maxDisInLast5sM));

        // 智驾运营看板扩展快照（V1.1）
        stats.put("autoDistanceM", round2(agg.autoDisSumM));
        stats.put("autoDurationS", round2(agg.autoDurSumS));
        stats.put("maxHnpDisM", round2(agg.maxHnpDisM));
        stats.put("maxUnpDisM", round2(agg.maxUnpDisM));
        stats.put("maxRnpDisM", round2(agg.maxRnpDisM));
        com.fasterxml.jackson.databind.node.ObjectNode parkingNode = stats.putObject("parking");
        agg.parking.forEach((func, pa) -> {
            com.fasterxml.jackson.databind.node.ObjectNode n = parkingNode.putObject(func);
            n.put("count", pa.count);
            n.put("durationS", round2(pa.durationS));
            n.put("gearSwitch", round2(pa.gearSwitch));
            n.put("success", pa.success);
            n.put("manual", pa.manual);
        });
        String sectionStatsJson = objectMapper.writeValueAsString(stats);

        // 8. 原始日志不再落对象存储：解析完成后 dataBytes 随方法返回被回收
        String packetId = UUID.randomUUID().toString();

        // 9. 插入 mxf_packet（含聚合快照）
        MxfPacket packet = new MxfPacket();
        packet.setId(packetId);
        packet.setFileName(fileName);
        packet.setFileMd5(md5);
        packet.setFileSize(fileSize);
        packet.setVin(blankToNull(vin));
        packet.setVinMd5(blankToNull(vinMd5));
        packet.setEventName(eventName);
        packet.setCarType(blankToNull(carType));
        packet.setProject(blankToNull(project));
        packet.setCollectType(blankToNull(collectType));
        packet.setTriggerTimestampUs(triggerUs);
        packet.setUtcDiffUs(utcDiffUs);
        packet.setTriggerTime(triggerTime);
        packet.setPacketStart(agg.firstTs);
        packet.setPacketEnd(agg.lastTs);
        packet.setRecordCount(agg.recordCount);
        packet.setSectionCount(agg.sectionCounts.size());
        packet.setSpecialEventCount(specialEventCount);
        packet.setStatus("PARSED");
        packet.setSource(blankToNull(source));
        packet.setDataSizeBytes(dataBytes == null ? 0L : (long) dataBytes.length);
        packet.setSectionStats(sectionStatsJson);
        packetMapper.insert(packet);

        // 10. 行驶轨迹点入库（每个 MXF 包 = 一段行驶轨迹）
        int trajPoints = saveTrajectoryPoints(packetId, agg.trajPoints);

        log.info("[MXF] imported file={} md5={} records={} sections={} event={} specialRecords={} trajPoints={}",
                fileName, md5, agg.recordCount, agg.sectionCounts.size(), eventName, specialEventCount,
                trajPoints);

        return MxfImportResultItem.builder()
                .fileName(fileName)
                .fileMd5(md5)
                .fileSize(fileSize)
                .success(true)
                .skipped(false)
                .packetId(packetId)
                .status("PARSED")
                .recordCount(agg.recordCount)
                .sectionCount(agg.sectionCounts.size())
                .build();
    }

    // ============================================================
    // tar/gzip 解压到内存
    // ============================================================
    private Map<String, byte[]> readTarEntries(TarArchiveInputStream tar) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        ArchiveEntry entry;
        byte[] buf = new byte[8192];
        while ((entry = tar.getNextEntry()) != null) {
            if (!tar.canReadEntryData(entry) || entry.isDirectory()) {
                continue;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int n;
            while ((n = tar.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            result.put(entry.getName(), baos.toByteArray());
        }
        return result;
    }

    private byte[] findMeta(Map<String, byte[]> entries) throws IOException {
        for (String name : entries.keySet()) {
            String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
            if (META_FILE.matcher(base).matches()) {
                return entries.get(name);
            }
        }
        throw new IOException("压缩包内未找到 meta 文件（期望文件名匹配 meta/metadata/info）");
    }

    private byte[] findData(Map<String, byte[]> entries) throws IOException {
        for (String name : entries.keySet()) {
            String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
            if (META_FILE.matcher(base).matches()) {
                continue;
            }
            return entries.get(name);
        }
        throw new IOException("压缩包内未找到日志数据文件");
    }

    // ============================================================
    // meta 解析（支持 key=value 与 JSON 两种格式）
    // ============================================================
    private Map<String, String> parseMeta(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        text = stripBom(text).trim();
        if (text.isEmpty()) {
            return Map.of();
        }
        // JSON 形式
        if (text.startsWith("{")) {
            try {
                Map<String, Object> obj = objectMapper.readValue(text, Map.class);
                Map<String, String> flat = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : obj.entrySet()) {
                    flat.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
                }
                return flat;
            } catch (Exception ignored) {
                // 非 JSON 则按 key=value 处理
            }
        }
        // key=value 形式
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher m = META_KV.matcher(line);
            if (m.matches()) {
                map.put(m.group(1), m.group(2));
            }
        }
        return map;
    }

    // ============================================================
    // 日志聚合：[ts]:[SECTION]:{json}
    //   - section 计数（SECTION 名以《MXF埋点数据开放接口定义_0911.xlsx》
    //     的「表名称」列为准，共 54 张表；动态统计、无白名单，
    //     示例 .data 只含少数几张表，不代表全集）
    //   - 8 类特殊事件计数（由 SECTION 名识别，见 MxfSpecialEvents）
    //   - AEB / APA / AVP 触发计数
    //   - RUNNING-INFO 里程/时长最大值
    // ============================================================
    private DataAggregate aggregateData(byte[] bytes) throws IOException {
        DataAggregate agg = new DataAggregate();
        if (bytes == null || bytes.length == 0) {
            return agg;
        }
        // SECTION → 特殊事件名 的解析缓存：同一 section 名只做一次归一化（value 允许为 null）
        Map<String, String> eventCache = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                Matcher m = LOG_LINE.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                String tsRaw = m.group(1);
                String section = m.group(2);
                String payload = m.group(3);
                agg.recordCount++;
                agg.sectionCounts.merge(section, 1L, Long::sum);

                // 8 类特殊事件：由 SECTION 名识别，逐行累加记录数
                String specialEvent;
                if (eventCache.containsKey(section)) {
                    specialEvent = eventCache.get(section);
                } else {
                    specialEvent = MxfSpecialEvents.resolveEvent(section);
                    eventCache.put(section, specialEvent);
                }
                if (specialEvent != null) {
                    agg.specialEventCounts.merge(specialEvent, 1L, Long::sum);
                }

                if (AEB_SECTIONS.contains(section)) {
                    agg.aebCount++;
                } else if (APA_SECTION.equals(section)) {
                    agg.apaCount++;
                } else if (AVP_SECTION.equals(section)) {
                    agg.avpCount++;
                }

                LocalDateTime logTs = parseLogTs(tsRaw);
                if (logTs != null) {
                    if (agg.firstTs == null) {
                        agg.firstTs = logTs;
                    }
                    agg.lastTs = logTs;
                }

                // 行驶轨迹点：pg / enter_pg / exit_pg / cur_pg（每个 MXF 包 = 一段行驶轨迹）
                collectTrajectoryPoints(section, specialEvent, payload, logTs, agg);

                // RUNNING-INFO：提取累计里程/时长、过去 5s 里程（均取包内最大值）
                if (RUNNING_SECTION.equals(section)) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(payload);
                        double dis = node.path(DISTANCE_FIELD).asDouble(Double.NaN);
                        if (!Double.isNaN(dis) && dis > agg.maxDistanceM) {
                            agg.maxDistanceM = dis;
                        }
                        double dur = node.path(DURATION_FIELD).asDouble(Double.NaN);
                        if (!Double.isNaN(dur) && dur > agg.maxDurationS) {
                            agg.maxDurationS = dur;
                        }
                        double dis5s = node.path(DIS_IN_LAST_5S_FIELD).asDouble(Double.NaN);
                        if (!Double.isNaN(dis5s) && dis5s > agg.maxDisInLast5sM) {
                            agg.maxDisInLast5sM = dis5s;
                        }
                    } catch (Exception ignored) {
                        // 单行 payload 非法不影响整体导入
                    }
                    continue;
                }

                // 智驾运营看板扩展统计：AUTO-INFO / HNP / UNP / PILOT / 泊车功能表
                collectOperationsStats(section, payload, agg);
            }
        }
        return agg;
    }

    /**
     * 按行提取智驾运营看板所需字段（非关注 SECTION 直接跳过；单行解析失败不影响整体导入）。
     *
     * <ul>
     *   <li>AUTO-INFO：auto_dis_in_last_duration_(m) / auto_duration_(s) 逐行累加；</li>
     *   <li>HNP/UNP/V2-RNP-INFO：单次智驾距离取包内最大值；</li>
     *   <li>泊车表：每行计 1 次使用，累计时长/揉库次数，统计成功（结束状态=2）与接管（type=manual）。</li>
     * </ul>
     */
    private void collectOperationsStats(String section, String payload, DataAggregate agg) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(payload);
            switch (section) {
                case AUTO_SECTION -> {
                    agg.autoDisSumM += node.path(AUTO_DIS_IN_LAST_FIELD).asDouble(0.0);
                    agg.autoDurSumS += node.path(AUTO_DUR_IN_LAST_FIELD).asDouble(0.0);
                }
                case HNP_SECTION -> {
                    double v = node.path(HNP_DIS_IN_LAST_FIELD).asDouble(Double.NaN);
                    if (!Double.isNaN(v) && v > agg.maxHnpDisM) {
                        agg.maxHnpDisM = v;
                    }
                }
                case UNP_SECTION -> {
                    double v = node.path(UNP_DIS_IN_LAST_FIELD).asDouble(Double.NaN);
                    if (!Double.isNaN(v) && v > agg.maxUnpDisM) {
                        agg.maxUnpDisM = v;
                    }
                }
                case RNP_SECTION -> {
                    double v = node.path(RNP_DIS_FIELD).asDouble(Double.NaN);
                    if (!Double.isNaN(v) && v > agg.maxRnpDisM) {
                        agg.maxRnpDisM = v;
                    }
                }
                default -> {
                    String[] cfg = PARKING_SECTIONS.get(section);
                    if (cfg == null) {
                        return;
                    }
                    ParkingAgg pa = agg.parking.computeIfAbsent(cfg[0], k -> new ParkingAgg());
                    pa.count++;
                    if (node.path(cfg[1]).asInt(0) == 2) {
                        pa.success++;
                    }
                    pa.durationS += node.path(cfg[2]).asDouble(0.0);
                    if (!cfg[3].isEmpty()) {
                        pa.gearSwitch += node.path(cfg[3]).asDouble(0.0);
                    }
                    com.fasterxml.jackson.databind.JsonNode type = node.path("type");
                    if (type.isTextual() && "manual".equalsIgnoreCase(type.asText())) {
                        pa.manual++;
                    }
                }
            }
        } catch (Exception ignored) {
            // 单行 payload 非法不影响整体导入
        }
    }

    /**
     * 逐行提取行驶轨迹点：pg / enter_pg / exit_pg / cur_pg。
     *
     * <p>归属规则：SECTION 命中特殊事件时，该行点位打上 {@code eventName}
     * （选择具体触发事件可单独显示对应轨迹线）；否则为包级轨迹点（eventName 为 null），
     * 按时间序即该包的完整行驶轨迹线。
     *
     * <p>连续重复坐标只保留一个（车辆静止时点位大量重复，去重可显著压缩存储）。
     */
    private void collectTrajectoryPoints(String section, String eventName, String payload,
                                         LocalDateTime logTs, DataAggregate agg) {
        if (payload == null || payload.isBlank() || agg.trajPoints.size() >= MAX_TRAJ_POINTS) {
            return;
        }
        // 快速前置判断：避免对每行都做 JSON 解析
        if (!payload.contains("\"pg\"") && !payload.contains("_pg\"")) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception ignored) {
            return;
        }
        for (Map.Entry<String, String> f : PG_FIELDS.entrySet()) {
            JsonNode v = node.get(f.getKey());
            if (v == null || v.isNull()) {
                continue;
            }
            double[] ll = parseLngLat(v);
            if (ll == null) {
                continue;
            }
            String key = eventName + "|" + f.getValue();
            double[] last = agg.lastTrajPoint.get(key);
            if (last != null && Math.abs(last[0] - ll[0]) < 1e-7 && Math.abs(last[1] - ll[1]) < 1e-7) {
                continue;
            }
            agg.lastTrajPoint.put(key, ll);
            if (agg.trajPoints.size() >= MAX_TRAJ_POINTS) {
                return;
            }
            MxfTrajectoryPoint p = new MxfTrajectoryPoint();
            p.setPacketId(null);   // 落库前统一回填
            p.setEventName(eventName);
            p.setSection(section);
            p.setPointType(f.getValue());
            p.setLogTs(logTs);
            p.setLon(ll[0]);
            p.setLat(ll[1]);
            agg.trajPoints.add(p);
        }
    }

    /**
     * 经纬度解析：支持 {@code "经度,纬度"} 字符串 / 数组 / {@code {lon,lat}} 对象。
     *
     * <p>MXF 实际点位形如 {@code [0.000000, 28.535112, 115.859192]}：首元素是占位 0，
     * 其后为纬度、经度（顺序不固定）。中国区域经度（约 73~135）恒大于纬度（约 18~54），
     * 因此按「多于两个值时取较大的两个数，其中大者为经度、小者为纬度」解析。
     *
     * @return [lon, lat]；非法或零点返回 null
     */
    private static double[] parseLngLat(JsonNode v) {
        double lon;
        double lat;
        if (v.isObject()) {
            // 对象形式字段名语义明确，直接按键取值
            lon = firstDouble(v, "lon", "lng", "longitude");
            lat = firstDouble(v, "lat", "latitude");
        } else {
            List<Double> vals = new ArrayList<>();
            if (v.isTextual()) {
                for (String part : v.asText().split(",")) {
                    try {
                        vals.add(Double.parseDouble(part.trim()));
                    } catch (NumberFormatException ignored) {
                        // 非数字片段忽略
                    }
                }
            } else if (v.isArray()) {
                for (JsonNode e : v) {
                    if (e != null && e.isNumber()) {
                        vals.add(e.asDouble());
                    }
                }
            } else {
                return null;
            }
            if (vals.size() < 2) {
                return null;
            }
            if (vals.size() > 2) {
                // 三个数（含占位 0）时只要较大的两个
                vals.sort((x, y) -> Double.compare(y, x));
                vals = vals.subList(0, 2);
            }
            // 中国区域：经度取大值，纬度取小值
            lon = Math.max(vals.get(0), vals.get(1));
            lat = Math.min(vals.get(0), vals.get(1));
        }
        if (Double.isNaN(lon) || Double.isNaN(lat)) {
            return null;
        }
        boolean valid = lon >= -180.0 && lon <= 180.0 && lat >= -90.0 && lat <= 90.0
                && !(Math.abs(lon) < 1e-9 && Math.abs(lat) < 1e-9);
        return valid ? new double[]{lon, lat} : null;
    }

    private static double firstDouble(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && v.isNumber()) {
                return v.asDouble();
            }
        }
        return Double.NaN;
    }

    /**
     * 轨迹点入库：一个 MXF 包 = 一段行驶轨迹
     *
     * @return 成功写入的点数
     */
    private int saveTrajectoryPoints(String packetId, List<MxfTrajectoryPoint> points) {
        if (points == null || points.isEmpty()) {
            return 0;
        }
        List<MxfTrajectoryPoint> batch = new ArrayList<>(TRAJ_INSERT_BATCH);
        int saved = 0;
        for (MxfTrajectoryPoint p : points) {
            p.setId(null);
            p.setPacketId(packetId);
            batch.add(p);
            if (batch.size() >= TRAJ_INSERT_BATCH) {
                saved += flushTrajectoryBatch(packetId, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            saved += flushTrajectoryBatch(packetId, batch);
        }
        return saved;
    }

    /** 批量写入一批轨迹点；整批失败时退化为逐条写入，避免一颗坏数据丢一批点 */
    private int flushTrajectoryBatch(String packetId, List<MxfTrajectoryPoint> batch) {
        try {
            // MyBatis-Plus 的批量插入（内部 ExecuteBatch），成功即整批写入
            trajectoryPointMapper.insert(batch);
            return batch.size();
        } catch (Exception e) {
            log.warn("[MXF] 轨迹点批量写入失败，改为逐条写入 packetId={} size={} err={}",
                    packetId, batch.size(), e.getMessage());
        }
        int saved = 0;
        for (MxfTrajectoryPoint p : batch) {
            try {
                p.setId(null);
                trajectoryPointMapper.insert(p);
                saved++;
            } catch (Exception ignored) {
                // 单点失败不影响其余轨迹点
            }
        }
        return saved;
    }

    /**
     * 清理存量原始日志对象（{@code mxf/packets/<id>/data.log}）。
     *
     * <p>导入已不再保存原始日志，这里用于回收历史遗留对象：逐个删除对象存储文件并清空
     * {@code mxf_packet.data_object_key}，<b>保留</b>包元数据与聚合快照，不影响任何看板与轨迹查询。</p>
     *
     * @param limit 本次最多处理包数（0 或负 = 不限）
     * @return 成功清理的对象个数
     */
    @Override
    public int purgeRawLogs(int limit) {
        LambdaQueryWrapper<MxfPacket> qw = new LambdaQueryWrapper<MxfPacket>()
                .isNotNull(MxfPacket::getDataObjectKey)
                .orderByAsc(MxfPacket::getCreatedAt);
        List<MxfPacket> packets = limit > 0
                ? packetMapper.selectList(qw.last("LIMIT " + limit))
                : packetMapper.selectList(qw);

        int purged = 0;
        for (MxfPacket p : packets) {
            String key = p.getDataObjectKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            try {
                storageAccess.delete(key);
            } catch (Exception e) {
                // 对象不存在 / 已归档等情况不阻断 DB 清理，只记录日志
                log.warn("[MXF] 原始日志对象删除失败 packetId={} key={} err={}",
                        p.getId(), key, e.getMessage());
            }
            try {
                packetMapper.update(null, new LambdaUpdateWrapper<MxfPacket>()
                        .set(MxfPacket::getDataObjectKey, null)
                        .eq(MxfPacket::getId, p.getId()));
                purged++;
            } catch (Exception e) {
                log.warn("[MXF] 原始日志引用清理失败 packetId={} err={}", p.getId(), e.getMessage());
            }
        }
        log.info("[MXF] 原始日志清理完成 packets={} purged={}", packets.size(), purged);
        return purged;
    }

    /**
     * 泊车功能包内聚合（APA/RPA/AVP/LPNP/NSP/LRAS）
     */
    private static final class ParkingAgg {
        long count;
        long success;
        long manual;
        double durationS;
        double gearSwitch;
    }

    private static final class DataAggregate {
        int recordCount;
        long aebCount;
        long apaCount;
        long avpCount;
        double maxDistanceM;
        double maxDurationS;
        /** RUNNING-INFO 过去 5s 行驶里程包内最大值（m），用于换算最高车速 */
        double maxDisInLast5sM;
        LocalDateTime firstTs;
        LocalDateTime lastTs;
        final Map<String, Long> sectionCounts = new LinkedHashMap<>();
        /**
         * 8 类特殊事件 → 记录数（只包含命中的事件）
         */
        final Map<String, Long> specialEventCounts = new LinkedHashMap<>();

        // ===== 智驾运营看板扩展（V1.1）=====
        /** AUTO-INFO 单次里程累加（m） */
        double autoDisSumM;
        /** AUTO-INFO 单次时长累加（s） */
        double autoDurSumS;
        /** HNP-INFO 单次高速智驾距离包内最大值（m） */
        double maxHnpDisM;
        /** UNP-INFO 单次城区智驾距离包内最大值（m） */
        double maxUnpDisM;
        /** V2-RNP-INFO 单次 NOA 漫游距离包内最大值（m） */
        double maxRnpDisM;
        /** 泊车功能键 → 包内聚合 */
        final Map<String, ParkingAgg> parking = new LinkedHashMap<>();

        // ===== 行驶轨迹（V1.1）=====
        /** 包内轨迹点（eventName 为空 = 包级完整轨迹；非空 = 触发事件轨迹） */
        final List<MxfTrajectoryPoint> trajPoints = new ArrayList<>();
        /** "eventName|pointType" → 上一个点坐标，用于连续重复点去重 */
        final Map<String, double[]> lastTrajPoint = new HashMap<>();

        /**
         * 主事件 = 包内记录数最多的特殊事件；记录数并列时取 {@link MxfSpecialEvents} 中优先级更高者。
         *
         * @return 事件名；包内没有任何特殊事件时返回 null
         */
        String primaryEventName() {
            String best = null;
            long bestCount = -1L;
            for (Map.Entry<String, Long> e : specialEventCounts.entrySet()) {
                long cnt = e.getValue() == null ? 0L : e.getValue();
                if (cnt > bestCount) {
                    best = e.getKey();
                    bestCount = cnt;
                } else if (cnt == bestCount && best != null
                        && MxfSpecialEvents.priorityOf(e.getKey()) < MxfSpecialEvents.priorityOf(best)) {
                    best = e.getKey();
                }
            }
            return best;
        }

        /**
         * 特殊事件记录总数（所有命中事件的记录数之和）。
         */
        int specialEventTotal() {
            long sum = 0L;
            for (Long v : specialEventCounts.values()) {
                sum += (v == null ? 0L : v);
            }
            return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
        }
    }

    private static LocalDateTime parseLogTs(String ts) {
        // 支持 [2026-09-15 00:27:53.871608208] 这种纳秒格式
        try {
            String norm = ts.trim();
            int dot = norm.indexOf('.');
            if (dot >= 0) {
                String head = norm.substring(0, dot);
                String tail = norm.substring(dot + 1);
                if (tail.length() > 6) {
                    tail = tail.substring(0, 6);
                }
                norm = head + "." + tail;
            }
            return LocalDateTime.parse(norm.replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime toUtcLocalDateTime(Long triggerUs, Long utcDiffUs) {
        if (triggerUs == null) {
            return null;
        }
        long us = utcDiffUs == null ? 0L : triggerUs - utcDiffUs;
        Instant inst = Instant.ofEpochSecond(us / 1_000_000L, (us % 1_000_000L) * 1000L);
        return LocalDateTime.ofInstant(inst, ZoneOffset.UTC);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    private static String stripBom(String s) {
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 把 meta 中的车辆标识解析成「明文 VIN + 该 VIN 的 md5」。
     *
     * <p>MXF 的 meta.vin 实测是 VIN 的 32 位 md5（偶发也直接给明文 VIN），
     * 因此统一经 {@code vin_md5_mapping} 换算：</p>
     * <ul>
     *   <li>meta.vin 是 md5 → 按 {@code md5} 查出明文 VIN（未命中则保留 meta 原值）；</li>
     *   <li>meta.vin 已是明文 VIN → 按 {@code vin} 查出 md5 补全；</li>
     *   <li>meta 另带 vin_md5 时优先用它反查。</li>
     * </ul>
     * 映射表查询失败不影响导入，降级为使用 meta 原值。
     */
    private ResolvedVin resolveVin(String metaVin, String metaVinMd5) {
        String vin = blankToNull(metaVin);
        String md5 = blankToNull(metaVinMd5);
        if (vin == null && md5 == null) {
            return new ResolvedVin(null, null);
        }
        try {
            // ① meta 自带 vin_md5：按摘要反查明文 VIN
            if (md5 != null) {
                VinMd5Mapping byMd5 = selectMapping(md5, true);
                if (byMd5 != null && blankToNull(byMd5.getVin()) != null) {
                    return new ResolvedVin(byMd5.getVin().trim(), md5);
                }
            }
            if (vin == null) {
                return new ResolvedVin(null, md5);
            }
            // ② meta.vin 是 32 位摘要：反查明文 VIN
            if (MD5_HEX.matcher(vin).matches()) {
                VinMd5Mapping byMd5 = selectMapping(vin, true);
                if (byMd5 != null && blankToNull(byMd5.getVin()) != null) {
                    return new ResolvedVin(byMd5.getVin().trim(), vin);
                }
                log.warn("[MXF] vin_md5_mapping 未命中摘要 vin={}，meta 原值落库", vin);
                return new ResolvedVin(vin, md5 != null ? md5 : vin);
            }
            // ③ meta.vin 已是明文 VIN：反查摘要补全 vin_md5
            if (md5 == null) {
                VinMd5Mapping byVin = selectMapping(vin, false);
                if (byVin != null) {
                    md5 = blankToNull(byVin.getMd5());
                }
            }
            return new ResolvedVin(vin, md5);
        } catch (Exception e) {
            log.warn("[MXF] vin_md5_mapping 查询失败，降级使用 meta 原值 vin={} err={}", vin, e.getMessage());
            return new ResolvedVin(vin, md5);
        }
    }

    /**
     * 按 md5（唯一索引）或 vin（唯一索引）查映射表，命中不到返回 null。
     */
    private VinMd5Mapping selectMapping(String value, boolean byMd5) {
        LambdaQueryWrapper<VinMd5Mapping> qw = new LambdaQueryWrapper<>();
        if (byMd5) {
            qw.eq(VinMd5Mapping::getMd5, value);
        } else {
            qw.eq(VinMd5Mapping::getVin, value);
        }
        return vinMd5MappingMapper.selectOne(qw.last("LIMIT 1"));
    }

    /**
     * meta 车辆标识的规范化结果。
     *
     * @param vin 明文 VIN（映射未命中时为 meta 原值）
     * @param md5 该 VIN 对应的 32 位摘要
     */
    private record ResolvedVin(String vin, String md5) {
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
