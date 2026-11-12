package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.MxfDashboardSummaryResponse;
import com.isim.dims.dto.MxfEventDistributionItem;
import com.isim.dims.dto.MxfKpiCardsResponse;
import com.isim.dims.dto.MxfOperationsResponse;
import com.isim.dims.dto.MxfSpecialEventAlert;
import com.isim.dims.entity.MxfPacket;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.MxfPacketMapper;
import com.isim.dims.service.MxfDashboardService;
import com.isim.dims.util.MxfSpecialEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MXF 看板聚合实现（V27 起）。
 *
 * <h3>数据来源</h3>
 * <ul>
 *   <li>「数据包总量」及下方柱状图改为统计 {@code data_slice} 表：时间范围内
 *       （{@code COALESCE(trigger_time, start_ts)}）的切片数量，柱状图按
 *       {@code event_name} 分类（Top N，其余聚合为「其他」）。</li>
 *   <li>「特殊事件通知」改为查 {@code data_slice}：最近 24 小时内 {@code event_name}
 *       命中 6 类特殊事件的切片，触发时间取 {@code trigger_time}（为空回退 {@code start_ts}）。</li>
 *   <li>KPI（里程/时长/AEB/APA/AVP）与特殊事件记录数来自 {@code mxf_packet.section_stats}（JSONB）
 *       聚合快照：{sections:{...}, events:{...}, aebCount, apaCount, avpCount, distanceKm, durationHours,
 *       autoDistanceM, autoDurationS, maxHnpDisM, maxUnpDisM, maxRnpDisM, parking}。</li>
 * </ul>
 *
 * <h3>时间筛选</h3>
 * <ul>
 *   <li>today：今天 00:00 UTC 起</li>
 *   <li>week：本周一 00:00 UTC 起（ISO 周）</li>
 *   <li>month：本月 1 号 00:00 UTC 起</li>
 *   <li>year：本年 1 月 1 日 00:00 UTC 起</li>
 *   <li>all：不过滤（trigger_time 为 NULL 的历史包只在 all 中出现）</li>
 *   <li>custom：由 {@code timeStart}/{@code timeEnd}（RFC3339）指定闭区间；缺省的一侧不设限</li>
 * </ul>
 *
 * @feature: mxf
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MxfDashboardServiceImpl implements MxfDashboardService {

    private static final int SECTION_TOP_N = 20;

    /**
     * 手动远光打点的两个事件名（见 CX835-MMT-筛选器.xlsx：同为「手动远光打点」）。
     *
     * <p>分布图里合并计数，只保留 {@link #HIGH_HIGHBEAM_ON} 一项。</p>
     */
    private static final String HIGH_HIGHBEAM_ON = "high_highbeam_on";

    private static final String MID_HIGHBEAM_ON = "mid_highbeam_on";

    /**
     * 特殊事件通知固定看最近 24 小时（与页面统计范围无关）
     */
    private static final int SPECIAL_ALERT_HOURS = 24;

    /**
     * all 范围 / 自定义范围未给下界：SQL 不接受 null 下界，用足够早的 UTC 时刻代表"不设限"
     */
    private static final LocalDateTime ALL_RANGE_FROM = LocalDateTime.of(1970, 1, 1, 0, 0);

    /**
     * 自定义范围未给上界：SQL 不接受 null 上界，用足够晚的 UTC 时刻代表"不设限"
     */
    private static final LocalDateTime ALL_RANGE_TO = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    /**
     * 自定义时间范围（由 timeStart / timeEnd 指定）在响应 timeRange 中的取值
     */
    private static final String CUSTOM_RANGE = "custom";

    private final MxfPacketMapper packetMapper;
    private final DataSliceMapper dataSliceMapper;
    private final ObjectMapper objectMapper;

    @Override
    public MxfDashboardSummaryResponse getSummary(String timeRange, String timeStart, String timeEnd,
                                                  int specialAlertLimit) {
        // 自定义范围优先：显式传入 timeStart / timeEnd 时忽略 timeRange 快捷范围
        LocalDateTime customStart = parseRfc3339Utc(timeStart);
        LocalDateTime customEnd = parseRfc3339Utc(timeEnd);
        boolean custom = customStart != null || customEnd != null;

        String range = custom ? CUSTOM_RANGE : normalizeTimeRange(timeRange);

        // 起点 / 终点（UTC）；null 表示该侧不设限。快捷范围只看起点，终点不设限
        LocalDateTime start = rangeStartUtc(range);
        LocalDateTime end = null;
        if (custom) {
            start = customStart;
            end = customEnd;
            if (start != null && end != null && start.isAfter(end)) {
                log.warn("[MXF] 自定义时间范围起止颠倒，已自动交换：timeStart={}, timeEnd={}", timeStart, timeEnd);
                LocalDateTime swap = start;
                start = end;
                end = swap;
            }
        }

        // data_slice 侧 SQL 不接受 null 边界，用哨兵值代表"不设限"
        LocalDateTime sliceFrom = start != null ? start : ALL_RANGE_FROM;
        LocalDateTime sliceTo = end != null ? end : ALL_RANGE_TO;

        // 1. 数据包总量：统计时间范围内 data_slice 表的数据（不再看 mxf_packet）
        Long sliceCount = dataSliceMapper.countMxfSlicesInRange(sliceFrom, sliceTo);
        long totalPackets = sliceCount == null ? 0L : sliceCount;

        // 2. 数据包总量柱状图：按 data_slice.event_name 分类统计切片数量（Top N + 其他）
        // 合并必须在聚合 / 取 Top N 之前做：
        //   ① 先聚合再删项会触发 ConcurrentModificationException（原实现在遍历中 remove）；
        //   ② 被挤出 Top N 的那部分会落进「其他」，事后无法再捞回来。
        List<MxfEventDistributionItem> eventNameDist = buildDistributionFromRows(
                mergeHighBeamOn(dataSliceMapper.selectMxfSliceCountByEventName(sliceFrom, sliceTo)), SECTION_TOP_N);

        // 3. mxf_packet：KPI（里程/时长/AEB/APA/AVP）与特殊事件记录数（按包内 SECTION 解析）
        QueryWrapper<MxfPacket> qw = new QueryWrapper<MxfPacket>().eq("status", "PARSED");
        if (start != null) {
            qw.ge("trigger_time", start);
        }
        if (end != null) {
            qw.le("trigger_time", end);
        }
        List<MxfPacket> packets = packetMapper.selectList(qw);

        long totalRecords = 0L;
        double totalDistanceKm = 0.0;
        double totalDurationHours = 0.0;
        long aebCount = 0L;
        long apaCount = 0L;
        long avpCount = 0L;
        // 8 类特殊事件名 → 记录数（来自 section_stats，见 MxfSpecialEvents）
        Map<String, Long> eventTotals = new LinkedHashMap<>();

        for (MxfPacket p : packets) {
            totalRecords += nvl(p.getRecordCount());

            Map<String, Long> packetEvents = new LinkedHashMap<>();
            String statsJson = p.getSectionStats();
            if (statsJson != null && !statsJson.isBlank()) {
                try {
                    JsonNode stats = objectMapper.readTree(statsJson);
                    JsonNode sections = stats.path("sections");

                    JsonNode events = stats.path("events");
                    if (events.isObject() && !events.isEmpty()) {
                        events.fields().forEachRemaining(e ->
                                packetEvents.merge(e.getKey(), e.getValue().asLong(0L), Long::sum));
                    } else {
                        // 兼容早期导入的包：当时快照里没有 events 字段，按同一规则从 sections 现算
                        collectSpecialEvents(sections, packetEvents);
                    }

                    aebCount += stats.path("aebCount").asLong(0L);
                    apaCount += stats.path("apaCount").asLong(0L);
                    avpCount += stats.path("avpCount").asLong(0L);
                    totalDistanceKm += stats.path("distanceKm").asDouble(0.0);
                    totalDurationHours += stats.path("durationHours").asDouble(0.0);
                } catch (Exception e) {
                    log.warn("[MXF] 坏的 section_stats，packetId={}", p.getId(), e);
                }
            }

            packetEvents.forEach((event, cnt) -> eventTotals.merge(event, cnt, Long::sum));
        }

        // 4. 特殊事件通知：最近 24 小时内 data_slice 命中指定事件名的切片
        //    （触发时间取 trigger_time，为空回退 start_ts；与统计范围无关）
        List<MxfSpecialEventAlert> alerts = loadRecentSpecialAlerts(specialAlertLimit);

        // 特殊事件记录总数（KPI 卡片与漏斗口径一致）
        long specialRecordCount = eventTotals.values().stream().mapToLong(Long::longValue).sum();

        MxfKpiCardsResponse kpi = MxfKpiCardsResponse.builder()
                .totalPackets(totalPackets)
                .totalRecords(totalRecords)
                .totalDrivingDistanceKm(round1(totalDistanceKm))
                .totalDrivingDurationHour(round1(totalDurationHours))
                .aebTriggerCount(aebCount)
                .apaTriggerCount(apaCount)
                .avpActivationCount(avpCount)
                .specialEventCount(specialRecordCount)
                .build();

        return MxfDashboardSummaryResponse.builder()
                .timeRange(range)
                .kpi(kpi)
                .eventNameDistribution(eventNameDist)
                .specialEventAlerts(alerts)
                .specialEventTotal(specialRecordCount)
                .build();
    }

    // ============================================================
    // 智驾运营数据看板（V1.1 重写版）
    // ============================================================

    /**
     * 行车看板：11 类触发事件（SECTION 名 → 显示名），触发次数 = 各 MXF 文件该 SECTION 记录数累加。
     */
    private static final Map<String, String> DRIVING_SECTIONS = buildDrivingSections();

    private static Map<String, String> buildDrivingSections() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("AEB-ACTIVATE", "AEB");
        m.put("FCW-ACTIVATE", "FCW");
        m.put("AES-ACTIVATE", "AES");
        m.put("ADB-ACTIVATE", "ADB");
        m.put("FCTA-ACTIVATE", "FCTA");
        m.put("FCTB-ACTIVATE", "FCTB");
        m.put("RCTB-ACTIVATE", "RCTB");
        m.put("CMSR-ACTIVATE", "CMSR");
        m.put("LKA-ACTIVATE", "LKA");
        m.put("LDW-ACTIVATE", "LDW");
        // LAEB（低速 AEB）：统计 MSP-BRAKE-ACTIVATE SECTION 记录数累加
        m.put("MSP-BRAKE-ACTIVATE", "LAEB");
        return m;
    }

    /** 泊车看板显示顺序：APA/RPA/AVP(原LPNP)/NSP/LRAS/S2S */
    private static final List<String> PARKING_FUNC_ORDER =
            List.of("APA", "RPA", "LPNP", "NSP", "LRAS", "S2S");

    /** 泊车看板显示名称：英文缩写 + 中文说明（LPNP 名称更改为 AVP，字段定义不变） */
    private static final Map<String, String> PARKING_FUNC_LABELS = Map.of(
            "APA", "APA（泊车辅助）",
            "RPA", "RPA（遥控泊车）",
            "LPNP", "AVP（记忆泊车）",
            "NSP", "NSP（窄道通行）",
            "LRAS", "LRAS（循迹倒车）",
            "S2S", "S2S（车位到车位）");

    @Override
    public MxfOperationsResponse getOperations(String timeRange, String timeStart, String timeEnd) {
        // 时间范围解析（与 getSummary 相同口径）
        LocalDateTime customStart = parseRfc3339Utc(timeStart);
        LocalDateTime customEnd = parseRfc3339Utc(timeEnd);
        boolean custom = customStart != null || customEnd != null;
        String range = custom ? CUSTOM_RANGE : normalizeTimeRange(timeRange);
        LocalDateTime start = rangeStartUtc(range);
        LocalDateTime end = null;
        if (custom) {
            start = customStart;
            end = customEnd;
            if (start != null && end != null && start.isAfter(end)) {
                log.warn("[MXF] 自定义时间范围起止颠倒，已自动交换：timeStart={}, timeEnd={}", timeStart, timeEnd);
                LocalDateTime swap = start;
                start = end;
                end = swap;
            }
        }

        QueryWrapper<MxfPacket> qw = new QueryWrapper<MxfPacket>().eq("status", "PARSED");
        if (start != null) {
            qw.ge("trigger_time", start);
        }
        if (end != null) {
            qw.le("trigger_time", end);
        }
        List<MxfPacket> packets = packetMapper.selectList(qw);

        double autoDisSumM = 0.0;
        double autoDurSumS = 0.0;
        double maxHnpDisM = 0.0;
        double maxUnpDisM = 0.0;
        double maxRnpDisM = 0.0;
        Set<String> allVins = new HashSet<>();
        Set<String> parkingVins = new HashSet<>();
        Map<String, Long> drivingTotals = new LinkedHashMap<>();
        Map<String, ParkingTotals> parkingTotals = new LinkedHashMap<>();

        for (MxfPacket p : packets) {
            String vin = p.getVin();
            boolean hasVin = vin != null && !vin.isBlank();
            if (hasVin) {
                allVins.add(vin);
            }

            String statsJson = p.getSectionStats();
            if (statsJson == null || statsJson.isBlank()) {
                continue;
            }
            try {
                JsonNode stats = objectMapper.readTree(statsJson);

                // KPI：辅助驾驶总里程/总时长（AUTO-INFO 逐行累加）
                autoDisSumM += stats.path("autoDistanceM").asDouble(0.0);
                autoDurSumS += stats.path("autoDurationS").asDouble(0.0);

                // KPI：最长智驾距离（单次）——所有 MXF 文件中取最大值
                maxHnpDisM = Math.max(maxHnpDisM, stats.path("maxHnpDisM").asDouble(0.0));
                maxUnpDisM = Math.max(maxUnpDisM, stats.path("maxUnpDisM").asDouble(0.0));
                maxRnpDisM = Math.max(maxRnpDisM, stats.path("maxRnpDisM").asDouble(0.0));

                // 行车看板：11 类触发事件累加
                JsonNode sections = stats.path("sections");
                DRIVING_SECTIONS.forEach((section, label) -> {
                    long c = sections.path(section).asLong(0L);
                    if (c > 0) {
                        drivingTotals.merge(label, c, Long::sum);
                    }
                });

                // 泊车看板：APA/RPA/LPNP/NSP/LRAS 聚合（AVP 已移除）
                JsonNode parkingNode = stats.path("parking");
                if (parkingNode.isObject()) {
                    parkingNode.fields().forEachRemaining(e -> {
                        String func = e.getKey();
                        if ("AVP".equals(func)) {
                            return; // 去掉 AVP 显示（lambda 中 return 等同于 continue）
                        }
                        ParkingTotals t = parkingTotals.computeIfAbsent(func, k -> new ParkingTotals());
                        JsonNode n = e.getValue();
                        t.count += n.path("count").asLong(0L);
                        t.durationS += n.path("durationS").asDouble(0.0);
                        t.gearSwitch += n.path("gearSwitch").asDouble(0.0);
                        t.success += n.path("success").asLong(0L);
                        t.manual += n.path("manual").asLong(0L);
                    });
                    if (!parkingNode.isEmpty() && hasVin) {
                        parkingVins.add(vin);
                    }
                }
                // S2S：从 sections.D2D-INFO 读取计数（车位到车位，只统计激活次数，其余不做统计）
                long s2sCount = sections.path("D2D-INFO").asLong(0L);
                if (s2sCount > 0) {
                    ParkingTotals t = parkingTotals.computeIfAbsent("S2S", k -> new ParkingTotals());
                    t.count += s2sCount;
                    // S2S 只统计激活次数，durationS/gearSwitch/success/manual 均保持 0，前端显示 "—"
                    if (hasVin) {
                        parkingVins.add(vin);
                    }
                }
            } catch (Exception e) {
                log.warn("[MXF] 坏的 section_stats，packetId={}", p.getId(), e);
            }
        }

        long parkingUseCount = parkingTotals.values().stream().mapToLong(t -> t.count).sum();
        long vehicleCount = allVins.size();

        List<MxfOperationsResponse.DrivingItem> driving = new ArrayList<>();
        DRIVING_SECTIONS.forEach((section, label) -> driving.add(MxfOperationsResponse.DrivingItem.builder()
                .key(section)
                .label(label)
                .count(drivingTotals.getOrDefault(label, 0L))
                .build()));
        // 行车看板：按触发次数由高到低排序
        driving.sort(Comparator.comparingLong(MxfOperationsResponse.DrivingItem::getCount).reversed());

        List<MxfOperationsResponse.ParkingItem> parking = new ArrayList<>();
        List<String> funcOrder = new ArrayList<>(PARKING_FUNC_ORDER);
        // 兼容未来新增的泊车功能：追加到固定顺序之后
        parkingTotals.keySet().stream().filter(k -> !funcOrder.contains(k)).forEach(funcOrder::add);
        for (String func : funcOrder) {
            ParkingTotals t = parkingTotals.getOrDefault(func, new ParkingTotals());
            boolean isS2S = "S2S".equals(func);
            // S2S 只统计激活次数，②③④⑤ 不做统计，前端显示 "—"
            parking.add(MxfOperationsResponse.ParkingItem.builder()
                    .key(func)
                    .label(PARKING_FUNC_LABELS.getOrDefault(func, func))
                    .avgUsePerVehicle(vehicleCount > 0 ? round2(t.count / (double) vehicleCount) : 0.0)
                    .avgDurationS(isS2S ? null : (t.count > 0 ? round2(t.durationS / t.count) : 0.0))
                    .avgGearSwitch(isS2S ? null : (t.count > 0 ? round2(t.gearSwitch / t.count) : 0.0))
                    .successRate(isS2S ? null : (t.count > 0 ? round4(t.success / (double) t.count) : 0.0))
                    .manualCount(isS2S ? null : t.manual)
                    .build());
        }

        return MxfOperationsResponse.builder()
                .timeRange(range)
                .autoDistanceKm(round2(autoDisSumM / 1000.0))
                .autoDurationHours(round2(autoDurSumS / 3600.0))
                .maxHnpDisM(round2(maxHnpDisM))
                .maxUnpDisM(round2(maxUnpDisM))
                .maxRnpDisM(round2(maxRnpDisM))
                .parkingUseCount(parkingUseCount)
                .vehicleCount(vehicleCount)
                .avgParkingUsePerVehicle(vehicleCount > 0 ? round2(parkingUseCount / (double) vehicleCount) : 0.0)
                .parkingVehicleRatio(vehicleCount > 0 ? round4(parkingVins.size() / (double) vehicleCount) : 0.0)
                .driving(driving)
                .parking(parking)
                .build();
    }

    /** 泊车功能跨包聚合（count=激活次数；durationS/gearSwitch 累加；success=结束状态2；manual=接管） */
    private static final class ParkingTotals {
        long count;
        long success;
        long manual;
        double durationS;
        double gearSwitch;
    }

    // ============================================================
    // 时间范围
    // ============================================================
    private static String normalizeTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            return "all";
        }
        switch (timeRange.trim().toLowerCase()) {
            case "today":
            case "week":
            case "month":
            case "year":
            case "all":
                return timeRange.trim().toLowerCase();
            default:
                return "all";
        }
    }

    /**
     * 返回范围起点（UTC）；all 返回 null
     */
    private static LocalDateTime rangeStartUtc(String range) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        switch (range) {
            case "today":
                return today.atStartOfDay();
            case "week":
                return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case "month":
                return today.withDayOfMonth(1).atStartOfDay();
            case "year":
                return today.withDayOfYear(1).atStartOfDay();
            default:
                return null;
        }
    }

    /**
     * 解析自定义时间范围参数，统一换算为 UTC。
     *
     * <p>支持 RFC3339（如 {@code 2026-09-01T00:00:00Z} / {@code 2026-09-01T00:00:00+08:00}）；
     * 不带偏移量的本地时间（如 {@code 2026-09-01T00:00:00}）按 UTC 处理。无法解析时返回 null。</p>
     */
    private static LocalDateTime parseRfc3339Utc(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 兼容不带偏移量的 ISO 本地时间
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("[MXF] 无法解析的时间参数，已忽略：{}", text);
            return null;
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 把 {@code mid_highbeam_on} 的计数并入 {@code high_highbeam_on}，只保留后者一项。
     *
     * <p>两者同属「手动远光打点」（CX835-MMT-筛选器.xlsx），分布图应算一根柱子而非两根。</p>
     *
     * <p>两个事件名都不存在时不新增条目（避免出现 count=0 的空柱子）。</p>
     */
    private static List<Map<String, Object>> mergeHighBeamOn(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object label = row == null ? null : row.get("label");
            Object cnt = row == null ? null : row.get("cnt");
            String key = label == null ? "N/A" : label.toString();
            if (MID_HIGHBEAM_ON.equals(key)) {
                key = HIGH_HIGHBEAM_ON;
            }
            merged.merge(key, cnt instanceof Number ? ((Number) cnt).longValue() : 0L, Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>(merged.size());
        merged.forEach((k, v) -> {
            Map<String, Object> row = new LinkedHashMap<>(2);
            row.put("label", k);
            row.put("cnt", v);
            out.add(row);
        });
        return out;
    }

    /**
     * 从 {@code section_stats.sections} 里捞出 8 类特殊事件的记录数。
     *
     * <p>仅用于兼容早期导入的包（当时快照里还没有 {@code events} 字段）。
     */
    private static void collectSpecialEvents(JsonNode sections, Map<String, Long> into) {
        if (sections == null || !sections.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = sections.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String event = MxfSpecialEvents.resolveEvent(e.getKey());
            if (event != null) {
                into.merge(event, e.getValue().asLong(0L), Long::sum);
            }
        }
    }

    /**
     * 特殊事件通知：最近 {@value #SPECIAL_ALERT_HOURS} 小时内 data_slice 命中的特殊事件切片。
     *
     * <p>命中事件名固定为 imu_collision / system_crash / cp_aes_trig_event /
     * cp_ess_trig_event / unp_tkov_colli / fushion_aeb（见 {@link DataSliceMapper}）。</p>
     *
     * <p>触发时间取 {@code trigger_time}，为空时回退切片 {@code start_ts}。</p>
     */
    private List<MxfSpecialEventAlert> loadRecentSpecialAlerts(int limit) {
        int max = Math.max(0, limit);
        if (max == 0) {
            return new ArrayList<>();
        }
        LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minusHours(SPECIAL_ALERT_HOURS);
        return dataSliceMapper.selectRecentMxfSpecialEventSlices(from, max).stream()
                .map(s -> MxfSpecialEventAlert.builder()
                        .id(s.getId())
                        .packetId(s.getId())
                        .vin(s.getVin())
                        .eventName(s.getEventName())
                        .title(MxfSpecialEvents.titleOf(s.getEventName()))
                        .triggerTime(s.getTriggerTime() != null ? s.getTriggerTime() : s.getStartTs())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 把 {@code SELECT label, cnt} 的聚合结果转成「Top N + 其他」分布条目。
     */
    private static List<MxfEventDistributionItem> buildDistributionFromRows(List<Map<String, Object>> rows, int topN) {
        Map<String, Long> totals = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Object label = row.get("label");
                Object cnt = row.get("cnt");
                totals.merge(label == null ? "N/A" : label.toString(),
                        cnt instanceof Number ? ((Number) cnt).longValue() : 0L, Long::sum);
            }
        }
        return buildDistribution(totals, topN);
    }

    private static List<MxfEventDistributionItem> buildDistribution(Map<String, Long> totals, int topN) {
        List<Map.Entry<String, Long>> sorted = totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList());

        List<MxfEventDistributionItem> items = new ArrayList<>();
        long shown = 0L;
        for (Map.Entry<String, Long> e : sorted) {
            if (items.size() >= topN) {
                break;
            }
            items.add(MxfEventDistributionItem.builder().label(e.getKey()).count(e.getValue()).build());
            shown += e.getValue();
        }
        // 超出 Top N 的聚合为"其他"
        long rest = totals.values().stream().mapToLong(Long::longValue).sum() - shown;
        if (rest > 0) {
            items.add(MxfEventDistributionItem.builder().label("其他").count(rest).build());
        }
        return items;
    }

    private static long nvl(Integer v) {
        return v == null ? 0L : v;
    }

    private static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
