package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.isim.dims.config.CameraEventConfig;
import com.isim.dims.config.MxfVehicleDashboardConfig;
import com.isim.dims.dto.MxfAbnormalPanelResponse;
import com.isim.dims.dto.MxfAbnormalPanelResponse.AbnormalVin;
import com.isim.dims.dto.MxfAbnormalPanelResponse.IgnoredVin;
import com.isim.dims.dto.MxfAbnormalPanelResponse.Thresholds;
import com.isim.dims.dto.MxfDailyReportResponse;
import com.isim.dims.dto.MxfEventRecord;
import com.isim.dims.dto.MxfFilterPanelResponse;
import com.isim.dims.dto.MxfFilterPanelResponse.FilterOption;
import com.isim.dims.dto.MxfKeyEventsPanelResponse;
import com.isim.dims.dto.MxfTrendPoint;
import com.isim.dims.dto.MxfVinPanelResponse;
import com.isim.dims.dto.MxfVinPanelResponse.VinOption;
import com.isim.dims.dto.MxfVinPanelResponse.VinRecord;
import com.isim.dims.dto.MxfWindowPanelResponse;
import com.isim.dims.dto.MxfWindowPanelResponse.EventCount;
import com.isim.dims.dto.MxfWindowPanelResponse.EventOption;
import com.isim.dims.dto.MxfWindowPanelResponse.Window;
import com.isim.dims.dto.MxfWindowPanelResponse.WindowStats;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.MxfOfflineNote;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.MxfOfflineNoteService;
import com.isim.dims.service.MxfVehicleDashboardService;
import com.isim.dims.util.MxfDailyReportEvents;
import com.isim.dims.util.MxfKeyEventsExcelWriter;
import com.isim.dims.util.MxfSpecialEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 单车埋点看板聚合实现。
 *
 * <h3>按页面 4 块区域拆成 4 个查询</h3>
 * <p>四个面板各自只算自己需要的数据 —— 这不只是「少返回字段」，还意味着<b>少发查询</b>：</p>
 * <ul>
 *   <li>左上（VIN）：切片列表 + VIN 聚合（车辆下拉要用包数与最后上传时间）；</li>
 *   <li>中上（筛选器）：只查切片列表，<b>不查</b> VIN 聚合；</li>
 *   <li>右列（窗口统计）：只查切片列表，<b>不查</b> VIN 聚合；</li>
 *   <li>左下（异常车辆）：查 VIN 聚合 + 备注表，<b>完全不碰切片列表</b>。</li>
 * </ul>
 *
 * <h3>数据来源：{@code data_slice}（2026-09-18 换源）</h3>
 * <p>原先读 {@code mxf_packet}，需求方更正为读 {@code data_slice}。两者是<b>两条互不重叠的
 * 入库链路</b>：{@code data_slice} 由本地目录导入 / CFDI 管道写入，{@code mxf_packet} 只由
 * {@code POST /v1/mxf/packets/upload} 写入。换源后手工导入的 MXF 包不再出现在看板上。</p>
 *
 * <table>
 *   <caption>字段映射</caption>
 *   <tr><th>语义</th><th>mxf_packet</th><th>data_slice</th></tr>
 *   <tr><td>记录 ID</td><td>id</td><td>id</td></tr>
 *   <tr><td>车辆</td><td>vin（32 位 md5，需反查）</td><td>vin（<b>明文 VIN</b>，写入方即
 *       {@code vin_md5_mapping.vin}，故不再反查）</td></tr>
 *   <tr><td>事件</td><td>event_name</td><td>event_name（{@code camera_event_config.json} 的 key）</td></tr>
 *   <tr><td>触发时刻</td><td>trigger_time</td><td>trigger_time</td></tr>
 *   <tr><td>兜底②</td><td>packet_start</td><td>start_ts</td></tr>
 *   <tr><td>上传时间</td><td>created_at</td><td>created_at</td></tr>
 *   <tr><td>文件名</td><td>file_name</td><td>name</td></tr>
 *   <tr><td>可下载</td><td>data_object_key 非空</td><td>{@code !StorageAccess#isArchived}：归档 ⇔
 *       <b>minio 模式 且 storage_type='LOCAL'</b>（方向勿反 —— LOCAL 是存量归档态，MINIO 是新导入可读态）。
 *       前端按钮置灰，真下载走 {@code /v1/data-slices/{id}/downloads/rosbag/ticket}</td></tr>
 *   <tr><td>项目 / 车型</td><td>car_type</td><td>vehicle_type（LEFT JOIN slice_stats）</td></tr>
 *   <tr><td>来源</td><td>source</td><td>source_type（MMT / ZYT / Hirain）</td></tr>
 * </table>
 *
 * <p><b>事件粒度</b>：仍为「一个切片最多贡献 1 次事件」（{@code event_name} 是切片级单值），
 * 与 v2 的包级口径一致，看板不展示切片内的其它事件。</p>
 *
 * <h3>触发时刻三级兜底（{@code effectiveTime}）</h3>
 * <ol>
 *   <li>{@code trigger_time}（本地导入按 {@code trigger_timestamp_us + utc_diff_us} 换算，
 *       <b>任一缺失即为 null</b>，实测 15 条只有 1 条有值）；</li>
 *   <li>{@code start_ts}（bag 采集起点）—— 需求方 2026-09-18 明确要求用它兜底。
 *       实测切片时长仅 30~35 秒，误差远小于积压阈值（天级）；</li>
 *   <li>{@code created_at}（入库时间）。</li>
 * </ol>
 * 仍保留年份合法性校验（∈ [2000, now+1]），防上游坏值（{@code 1970-01-01}）。
 * 窗口过滤、趋势分桶、明细「触发时刻」统一用该值。
 *
 * <h3>积压判定（替换掉线判定）</h3>
 * <p>每 VIN 取 {@code created_at} 最大的切片，{@code backlogDays = days(created_at − effectiveTime)}，
 * 超过 {@code dims.mxf.vehicle.backlog-delay-days}（默认 2）即判为数据积压待传。
 * <b>不受右列统计窗口（某一天）限制</b>：积压车的最新切片本就不在窗口内，加窗口会把真积压车全部漏掉。
 * 若兜底链退到 {@code created_at}，则延迟恒为 0（无法判断时不报警）。</p>
 *
 * @feature: mxf
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MxfVehicleDashboardServiceImpl implements MxfVehicleDashboardService {

    /** 展示时区（趋势按天分桶用） */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    private static final int MIN_VALID_YEAR = 2000;
    private static final int MAX_TREND_DAYS = 90;
    private static final int MAX_RECORD_LIMIT = 500;

    /** 右列窗口跨度：统计粒度是整天，恒为 24 小时（Asia/Shanghai 无夏令时） */
    private static final int HOURS_PER_DAY = 24;

    /**
     * 「重点事件统计」<b>兼容模式</b>的滚动窗口跨度（小时）。
     *
     * <p>只在三个时间参数全空（旧调用方）时使用；正常请求由顶部时间范围决定窗口。
     * 固定 24 不做配置项，避免验收数字漂移。</p>
     */
    private static final int KEY_EVENT_HOURS = 24;

    /** 兼容模式的 preset 值：前端据此显示「过去 24H 重点事件统计」 */
    private static final String PRESET_ROLLING_24H = "rolling24h";

    /**
     * 导出的行数安全上限。
     *
     * <p>页面明细为了渲染只取 {@code max-records}（默认 200）行，导出要的是「筛选条件下的全部」，
     * 所以放宽到 2 万行；再大就不是「点上导出」该干的事了（SXSSF 流式写 + 异步任务才合适）。</p>
     */
    private static final int EXPORT_MAX_ROWS = 20000;

    /**
     * 「全部」范围的哨兵上下界。
     *
     * <p>刻意不用 {@code null}：{@code null} 会逼着每条 SQL 都写一层 {@code <if>}，
     * 而 1970/9999 这两个值对 {@code timestamp} 列等价于「不设限」，SQL 保持单一路径。</p>
     */
    private static final LocalDateTime ALL_RANGE_FROM = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime ALL_RANGE_TO = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    /** 合法来源值（与 V19 迁移里 data_slice.source_type 的 CHECK 约束一致） */
    private static final Set<String> SOURCE_TYPES = Set.of("MMT", "ZYT", "Hirain");

    /**
     * {@code dims.mxf.vehicle.event-options} 为空时的兜底（原型定案的 8 个重点事件）。
     *
     * <p>正常情况下走配置；这里兜底是为了「yml 覆盖成空列表」时不至于抛
     * {@code IN ()} 语法错误。</p>
     */
    private static final List<String> FALLBACK_KEY_EVENTS = List.of(
            "imu_collision", "system_crash", "fushion_aeb", "cp_aes_trig_event",
            "cp_ess_trig_event", "meb_brake_event", "unp_tkov_colli", "dssad_collision");

    /** 右列 {@code date} 参数格式：严格 {@code yyyy-MM-dd}（月日必须补零，避免 "2026-9-1" 这类歧义写法） */
    private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DataSliceMapper dataSliceMapper;
    private final CameraEventConfig cameraEventConfig;
    private final MxfOfflineNoteService offlineNoteService;
    private final MxfVehicleDashboardConfig config;
    /** 归档判定唯一来源：存储模式 × storage_type，本服务不自行推导该规则 */
    private final StorageAccess storageAccess;

    // ============================================================
    // 面板 1：左上「单车视角」（查切片列表 + VIN 聚合）
    // ============================================================

    @Override
    public MxfVinPanelResponse getVinPanel(String vin, Integer trendDays, Integer recordLimit) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int trendDayCount = clamp(trendDays, 1, MAX_TREND_DAYS, config.getTrendDays());
        int recordCap = clamp(recordLimit, 1, MAX_RECORD_LIMIT, config.getMaxRecords());

        List<DataSlice> slices = loadSlices();
        // VIN 下拉走 DB GROUP BY，不受上面的 LIMIT 影响（否则老车会被截断漏报）。
        // v5 遗留面板不按来源过滤（下游仍有全量调用方），故传 null。
        List<VinAggregate> aggregates = loadVinAggregates(null);

        List<VinOption> vinOptions = buildVinOptions(aggregates);
        VinOption selectedVin = pickVin(vinOptions, vin);

        // 选中车辆的切片：不分窗口（左列展示该车全部切片，只受 recordLimit 限制）
        List<SliceView> vinSlices = selectedVin == null
                ? List.of()
                : toViews(slices, now).stream()
                        .filter(v -> sameVin(v.slice.getVin(), selectedVin.getVin()))
                        .sorted(SLICE_TIME_DESC)
                        .collect(Collectors.toList());

        return MxfVinPanelResponse.builder()
                .generatedAt(now)
                .trendDays(trendDayCount)
                .vinOptions(vinOptions)
                .selectedVin(selectedVin)
                .vinTrend(buildTrend(vinSlices, trendDayCount))
                .vinRecords(vinSlices.stream()
                        .limit(recordCap)
                        .map(this::toVinRecord)
                        .collect(Collectors.toList()))
                .build();
    }

    // ============================================================
    // 面板 2：中上「筛选器视角」（只查切片列表）
    // ============================================================

    @Override
    public MxfFilterPanelResponse getFilterPanel(String filter, String event,
                                                 Integer trendDays, Integer recordLimit) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int trendDayCount = clamp(trendDays, 1, MAX_TREND_DAYS, config.getTrendDays());
        int recordCap = clamp(recordLimit, 1, MAX_RECORD_LIMIT, config.getMaxRecords());

        List<FilterOption> filterOptions = buildFilterOptions();
        FilterSelection selection = resolveFilter(filter, event, filterOptions);
        List<String> middleEvents = selection.eventNames();

        List<DataSlice> slices = loadSlices();

        // 一个筛选器可能映射多个 event_name（如 highbeam_on_filter → 高中/中远光），
        // 取并集命中；行内仍然展示该切片真实命中的 event_name，便于区分是哪个变体。
        List<SliceView> eventSlices = middleEvents.isEmpty()
                ? List.of()
                : toViews(slices, now).stream()
                        .filter(v -> v.eventName != null && middleEvents.contains(v.eventName))
                        .sorted(SLICE_TIME_DESC)
                        .collect(Collectors.toList());

        return MxfFilterPanelResponse.builder()
                .generatedAt(now)
                .trendDays(trendDayCount)
                .filterOptions(filterOptions)
                .selectedFilter(selection.filter())
                .selectedFilterEvents(middleEvents)
                .selectedEvent(middleEvents.size() == 1 ? middleEvents.get(0) : null)
                .eventTrend(buildTrend(eventSlices, trendDayCount))
                .eventRecords(eventSlices.stream()
                        .limit(recordCap)
                        .map(this::toEventRecord)
                        .collect(Collectors.toList()))
                .build();
    }

    // ============================================================
    // 面板 3：右列「指定日期全天重点事件统计」（只查切片列表）
    // ============================================================

    @Override
    public MxfWindowPanelResponse getWindowPanel(List<String> events, String date, Integer recordLimit) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int recordCap = clamp(recordLimit, 1, MAX_RECORD_LIMIT, config.getMaxRecords());
        // 统计粒度是「北京时间自然日」：先定日期，再换算成 UTC 区间与库里的 UTC 时间列比较
        LocalDate targetDay = resolveTargetDay(date);
        LocalDateTime windowStart = beijingDayStartUtc(targetDay);
        LocalDateTime windowEnd = beijingDayStartUtc(targetDay.plusDays(1));

        List<String> knownNames = new ArrayList<>(cameraEventConfig.getEvents().keySet());
        List<String> eventOptionNames = resolveEventOptions(knownNames);
        List<String> selectedEvents = normalizeEvents(events, knownNames);

        List<DataSlice> slices = loadSlices();

        // 自然日区间左闭右开：次日 00:00 整点的切片归次日，日界数据不会被两天各算一次
        List<SliceView> inWindow = toViews(slices, now).stream()
                .filter(v -> v.effectiveTime != null)
                .filter(v -> !v.effectiveTime.isBefore(windowStart) && v.effectiveTime.isBefore(windowEnd))
                .collect(Collectors.toList());

        // 次数以「切片」为单位：一个切片最多贡献 1 次事件
        List<EventCount> byEvent = new ArrayList<>(selectedEvents.size());
        long totalTriggers = 0L;
        for (String name : selectedEvents) {
            long count = inWindow.stream().filter(v -> v.hasEvent(name)).count();
            byEvent.add(EventCount.builder()
                    .eventName(name)
                    .title(MxfSpecialEvents.titleOf(name))
                    .count(count)
                    .build());
            totalTriggers += count;
        }

        List<MxfEventRecord> windowRecords = inWindow.stream()
                .filter(v -> v.eventName != null && selectedEvents.contains(v.eventName))
                .map(this::toEventRecord)
                .collect(Collectors.toList());
        windowRecords.sort(RECORD_TIME_DESC);
        if (windowRecords.size() > recordCap) {
            windowRecords = new ArrayList<>(windowRecords.subList(0, recordCap));
        }

        long totalVehicles = inWindow.stream()
                .map(v -> v.slice.getVin())
                .filter(MxfVehicleDashboardServiceImpl::isNotBlank)
                .distinct()
                .count();

        return MxfWindowPanelResponse.builder()
                .generatedAt(now)
                .window(Window.builder()
                        .start(windowStart)
                        .end(windowEnd)
                        .date(targetDay)
                        .hours(HOURS_PER_DAY)
                        .build())
                .eventOptions(eventOptionNames.stream()
                        .map(name -> EventOption.builder()
                                .name(name)
                                .title(MxfSpecialEvents.titleOf(name))
                                .selected(selectedEvents.contains(name))
                                .build())
                        .collect(Collectors.toList()))
                .selectedEvents(selectedEvents)
                .windowStats(WindowStats.builder()
                        .totalVehicles(totalVehicles)
                        .totalTriggers(totalTriggers)
                        .project(resolveProject(inWindow))
                        .byEvent(byEvent)
                        .build())
                .windowRecords(windowRecords)
                .build();
    }

    // ============================================================
    // 面板 4：左下「数据异常车辆」（查 VIN 聚合 + 备注表）
    // ============================================================

    @Override
    public MxfAbnormalPanelResponse getAbnormalPanel() {
        // 无参 = 不按来源过滤（向后兼容，v5 前端与既有调用方行为不变）
        return getAbnormalPanel(null);
    }

    @Override
    public MxfAbnormalPanelResponse getAbnormalPanel(String sourceType) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        List<VinAggregate> aggregates = loadVinAggregates(normalizeSourceType(sourceType));
        // 忽略名单（填过备注的车辆不参与异常统计）
        Map<String, MxfOfflineNote> ignoredNotes = indexNotes(loadNotes());

        List<AbnormalVin> abnormalVehicles = buildAbnormalVehicles(aggregates, ignoredNotes, now);
        List<IgnoredVin> ignoredVehicles = buildIgnoredVehicles(aggregates, ignoredNotes);

        int abnormalCount = abnormalVehicles.size();
        return MxfAbnormalPanelResponse.builder()
                .generatedAt(now)
                .thresholds(Thresholds.builder()
                        .backlogDelayDays(config.getBacklogDelayDays())
                        .backlogVehicles(config.getBacklogVehicles())
                        .abnormalCount(abnormalCount)
                        .backlogExceeded(abnormalCount > config.getBacklogVehicles())
                        .ignoredCount(ignoredVehicles.size())
                        .build())
                .abnormalVehicles(abnormalVehicles)
                .ignoredVehicles(ignoredVehicles)
                .build();
    }

    // ============================================================
    // 面板 5：日报摘要 + 事件类型分布（v6）
    // ============================================================

    @Override
    public MxfDailyReportResponse getDailyReport(String sourceType, String timeRange,
                                                 String startDate, String endDate) {
        String source = normalizeSourceType(sourceType);
        ReportRange range = resolveReportRange(timeRange, startDate, endDate);

        // 两条 SQL、同一个窗口：摘要的总次数与 19 桶合计必须同源同时刻，否则页面会出现
        // 「摘要 N 次、各桶加起来 N±1」这种无法解释的差异
        Map<String, Object> totals = firstRow(
                dataSliceMapper.selectDailyReportTotals(source, range.from(), range.to()));
        List<Map<String, Object>> rows = safe(
                dataSliceMapper.selectDailyReportEventCounts(source, range.from(), range.to()));

        // 19 个桶先全部置 0：条形图要求「无值也占一行」
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String key : MxfDailyReportEvents.keySet()) {
            counts.put(key, 0L);
        }
        for (Map<String, Object> row : rows) {
            String bucket = MxfDailyReportEvents.classify(asString(row.get("event_name")));
            if (bucket != null) {
                counts.merge(bucket, asLong(row.get("cnt")), Long::sum);
            }
        }

        long triggers = asLong(totals == null ? null : totals.get("total_triggers"));
        long bucketed = counts.values().stream().mapToLong(Long::longValue).sum();
        // 用减法而不是在 SQL 里再排除一遍：obf_* / 空 event_name / 多事件包里没取到主事件的，
        // 全部自动落进「其他」，Σ(19 桶) + 未归类 == 总触发 由构造保证
        long unclassified = Math.max(0L, triggers - bucketed);

        List<MxfDailyReportResponse.EventBucket> distribution = MxfDailyReportEvents.keySet().stream()
                .map(key -> MxfDailyReportResponse.EventBucket.builder()
                        .key(key)
                        .title(MxfDailyReportEvents.titleOf(key))
                        .count(counts.getOrDefault(key, 0L))
                        .build())
                .toList();

        return MxfDailyReportResponse.builder()
                .generatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .range(MxfDailyReportResponse.Range.builder()
                        .preset(range.preset())
                        .start(range.from())
                        .end(range.to())
                        .build())
                .summary(MxfDailyReportResponse.Summary.builder()
                        .totalVehicles(asLong(totals == null ? null : totals.get("total_vehicles")))
                        .totalTriggers(triggers)
                        .totalFiles(asLong(totals == null ? null : totals.get("total_files")))
                        .totalBytes(asLong(totals == null ? null : totals.get("total_bytes")))
                        .build())
                .eventDistribution(distribution)
                .unclassifiedCount(unclassified)
                .build();
    }

    // ============================================================
    // 面板 6：重点事件统计（v6，窗口跟随顶部时间范围）
    // ============================================================

    @Override
    public MxfKeyEventsPanelResponse getKeyEvents(String sourceType, Integer recordLimit) {
        // 兼容旧签名 = 三个时间参数全空 → 滚动 24h
        return getKeyEvents(sourceType, recordLimit, null, null, null);
    }

    @Override
    public MxfKeyEventsPanelResponse getKeyEvents(String sourceType, Integer recordLimit,
                                                  String timeRange, String startDate,
                                                  String endDate) {
        String source = normalizeSourceType(sourceType);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 窗口与「事件类型分布」同源（北京时间自然日、左闭右开）；三个时间参数全空时退回滚动 24h
        ReportRange range = resolveKeyEventRange(timeRange, startDate, endDate, now);
        int limit = clamp(recordLimit, 1, MAX_RECORD_LIMIT, config.getMaxRecords());
        List<String> events = keyEventNames();

        // 计数单独查、不取 records.size()：明细被 recordLimit 截断时，计数仍然是真值
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String event : events) {
            counts.put(event, 0L);
        }
        for (Map<String, Object> row : safe(
                dataSliceMapper.selectKeyEventCounts(source, events, range.from(), range.to()))) {
            String name = asString(row.get("event_name"));
            if (name != null) {
                counts.merge(name, asLong(row.get("cnt")), Long::sum);
            }
        }

        List<MxfEventRecord> records = new ArrayList<>();
        for (DataSlice slice : safe(
                dataSliceMapper.selectKeyEventSlices(
                        source, events, range.from(), range.to(), limit))) {
            records.add(toEventRecord(new SliceView(slice,
                    // now 只用于年份合法性（[2000, now.year+1]），不参与窗口过滤
                    effectiveTime(slice.getTriggerTime(), slice.getStartTs(),
                            slice.getCreatedAt(), now),
                    blankToNull(slice.getEventName()))));
        }
        records.sort(RECORD_TIME_DESC);

        return MxfKeyEventsPanelResponse.builder()
                .generatedAt(now)
                .window(MxfKeyEventsPanelResponse.Window.builder()
                        .preset(range.preset())
                        .start(range.from())
                        .end(range.to())
                        .hours(hoursOf(range))
                        .build())
                .events(events.stream()
                        .map(name -> MxfKeyEventsPanelResponse.EventCount.builder()
                                .eventName(name)
                                .title(MxfSpecialEvents.titleOf(name))
                                .count(counts.getOrDefault(name, 0L))
                                .build())
                        .toList())
                .records(records)
                .build();
    }

    /**
     * 重点事件的统计窗口。
     *
     * <p>三个时间参数<b>全空</b>时走兼容分支（滚动 24h，preset={@code rolling24h}），
     * 保证老调用方的数字一字不变；只要给了任一有效时间参数，就完全复用日报的
     * {@link #resolveReportRange}，避免两张卡出现「同一屏两个窗口」。</p>
     *
     * <p>非法 preset / 单侧日期由 {@code resolveReportRange} 内部回退 today 并打 WARN。</p>
     */
    private ReportRange resolveKeyEventRange(String timeRange, String startDate,
                                             String endDate, LocalDateTime now) {
        boolean legacy = !isNotBlank(timeRange)
                && parseRangeDay(startDate) == null
                && parseRangeDay(endDate) == null;
        if (legacy) {
            return new ReportRange(PRESET_ROLLING_24H, now.minusHours(KEY_EVENT_HOURS), now);
        }
        return resolveReportRange(timeRange, startDate, endDate);
    }

    /**
     * 窗口跨度（小时）。
     *
     * <p>{@code all} 用 1970/9999 哨兵，跨度约 7000 万小时、无业务意义，返回 {@code null}；
     * 月份长度不固定（672/744），所以按实际 from/to 计算而不是写死。</p>
     */
    private static Integer hoursOf(ReportRange range) {
        if ("all".equals(range.preset())) {
            return null;
        }
        return (int) ChronoUnit.HOURS.between(range.from(), range.to());
    }

    @Override
    public byte[] exportKeyEventsXlsx(String sourceType, Integer recordLimit, String timeRange,
                                      String startDate, String endDate, String event) {
        String source = normalizeSourceType(sourceType);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 与页面同一套窗口解析：导出文件里的数据必须和屏幕上看到的筛选口径完全一致
        ReportRange range = resolveKeyEventRange(timeRange, startDate, endDate, now);
        List<String> events = resolveExportEvents(event);
        // 页面为了渲染只取 max-records，导出要「筛选条件下的全部」，故用单独的 2 万行上限
        int cap = clamp(recordLimit, 1, EXPORT_MAX_ROWS, EXPORT_MAX_ROWS);

        List<MxfEventRecord> records = new ArrayList<>();
        for (DataSlice slice : safe(dataSliceMapper.selectKeyEventSlices(
                source, events, range.from(), range.to(), cap))) {
            records.add(toEventRecord(new SliceView(slice,
                    effectiveTime(slice.getTriggerTime(), slice.getStartTs(),
                            slice.getCreatedAt(), now),
                    blankToNull(slice.getEventName()))));
        }
        records.sort(RECORD_TIME_DESC);
        log.info("[MXF] 导出重点事件统计：source={} preset={} events={} rows={}",
                source, range.preset(), events, records.size());
        return MxfKeyEventsExcelWriter.write(records);
    }

    /**
     * 导出用的事件清单。
     *
     * <p>页面下拉选中的是单个事件名（{@code keyFilter}），未选时表示「全部事件类型」。
     * 传入的值若不在 8 项清单内（例如早期前端传了筛选器名），按全部导出并留 WARN ——
     * 导出是「多给数据」比「报错让人重选」更可用的场景。</p>
     */
    private List<String> resolveExportEvents(String event) {
        List<String> all = keyEventNames();
        if (!isNotBlank(event)) {
            return all;
        }
        String trimmed = event.trim();
        if (all.contains(trimmed)) {
            return List.of(trimmed);
        }
        log.warn("[MXF] 导出时传入的 event={} 不在重点事件清单内，按全部 {} 项导出", trimmed, all.size());
        return all;
    }

    // ============================================================
    // 查询
    // ============================================================

    /**
     * 看板用切片列表（限流，按入库时间倒序）。
     *
     * <p>SQL 里已排除 {@code status='ERROR'}（导入失败的切片不算已上传数据），
     * 并 LEFT JOIN {@code slice_stats} 取车型 —— 见
     * {@link DataSliceMapper#selectDashboardRows(int)}。</p>
     */
    private List<DataSlice> loadSlices() {
        List<DataSlice> rows = dataSliceMapper.selectDashboardRows(Math.max(1, config.getMaxPackets()));
        return rows == null ? List.of() : rows;
    }

    /**
     * VIN 维度聚合（DB 侧 GROUP BY）：切片数 + 最后上传时间 + <b>最新切片</b>的触发/采集起点时间
     * + 最新切片的软件版本。
     *
     * <p>积压判定只需要「最新切片」的触发时间，用 PG 的
     * {@code (array_agg(col ORDER BY created_at DESC))[1]} 在同一个聚合查询里取出，
     * 避免再发一次查询、也避免走内存结果被条数上限截断（老车漏报）。</p>
     *
     * @param sourceType 数据来源提供方（MMT/ZYT/Hirain）；null = 不过滤
     */
    private List<VinAggregate> loadVinAggregates(String sourceType) {
        QueryWrapper<DataSlice> qw = new QueryWrapper<DataSlice>()
                .select("vin",
                        "count(*) AS packet_count",
                        "max(created_at) AS last_created_at",
                        "(array_agg(trigger_time ORDER BY created_at DESC))[1] AS last_trigger_time",
                        "(array_agg(start_ts ORDER BY created_at DESC))[1] AS last_start_ts",
                        // 软件版本：取最新切片的 system_name（V30 新增列，老数据为 null）
                        "(array_agg(system_name ORDER BY created_at DESC))[1] AS last_system_name")
                .ne("status", "ERROR")
                .isNotNull("vin")
                .ne("vin", "")
                .eq(sourceType != null, "source_type", sourceType)
                .groupBy("vin");
        List<Map<String, Object>> rows = dataSliceMapper.selectMaps(qw);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<VinAggregate> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String vin = asString(row.get("vin"));
            if (!isNotBlank(vin)) {
                continue;
            }
            result.add(new VinAggregate(vin,
                    asLong(row.get("packet_count")),
                    asDateTime(row.get("last_created_at")),
                    asDateTime(row.get("last_trigger_time")),
                    asDateTime(row.get("last_start_ts")),
                    blankToNull(asString(row.get("last_system_name")))));
        }
        result.sort(AGG_LAST_CREATED_DESC);
        return result;
    }

    private List<MxfOfflineNote> loadNotes() {
        try {
            List<MxfOfflineNote> rows = offlineNoteService.listAll();
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            // 备注表缺失 / 查询失败不应让整个看板挂掉，降级为「无忽略车辆」
            log.warn("[MXF] 忽略名单查询失败，按无忽略处理", e);
            return List.of();
        }
    }

    // ============================================================
    // 组装
    // ============================================================

    /**
     * 车辆下拉项。
     *
     * <p>{@code data_slice.vin} 已是明文 VIN（写入方取的就是 {@code vin_md5_mapping.vin}），
     * 因此不再需要 md5 反查：非空即视为已识别（{@code resolved=true}）。
     * {@code display} 直接用原值，仅对空值做兜底。</p>
     */
    private static List<VinOption> buildVinOptions(List<VinAggregate> aggregates) {
        List<VinOption> options = new ArrayList<>(aggregates.size());
        for (VinAggregate a : aggregates) {
            String vin = blankToNull(a.vin());
            options.add(VinOption.builder()
                    .vin(a.vin())
                    .display(vin == null ? "" : vin)
                    .resolved(vin != null)
                    .packetCount(a.packetCount())
                    .lastUploadAt(a.lastCreatedAt())
                    .build());
        }
        return options;
    }

    private static VinOption pickVin(List<VinOption> options, String vin) {
        if (options.isEmpty()) {
            return null;
        }
        if (isNotBlank(vin)) {
            String target = vin.trim();
            for (VinOption o : options) {
                if (target.equals(o.getVin()) || target.equals(o.getDisplay())) {
                    return o;
                }
            }
            log.debug("[MXF] 传入的 vin={} 不在车辆列表中，回退到最近上传的车辆", target);
        }
        // options 已按最后上传时间倒序
        return options.get(0);
    }

    /**
     * 积压车辆：每 VIN 按「最新切片」判定 {@code created_at − effectiveTime > 阈值}。
     *
     * <p>已填备注的 VIN 直接跳过（改由 {@link #buildIgnoredVehicles} 输出）。</p>
     */
    private List<AbnormalVin> buildAbnormalVehicles(List<VinAggregate> aggregates,
                                                    Map<String, MxfOfflineNote> ignoredNotes,
                                                    LocalDateTime now) {
        List<AbnormalVin> result = new ArrayList<>();
        for (VinAggregate a : aggregates) {
            if (a.lastCreatedAt() == null || ignoredNotes.containsKey(a.vin())) {
                continue;
            }
            LocalDateTime trigger = effectiveTime(
                    a.lastTriggerTime(), a.lastStartTs(), a.lastCreatedAt(), now);
            long backlogDays = ChronoUnit.DAYS.between(trigger, a.lastCreatedAt());
            if (backlogDays > config.getBacklogDelayDays()) {
                String vin = blankToNull(a.vin());
                result.add(AbnormalVin.builder()
                        .vin(a.vin())
                        .display(vin == null ? "" : vin)
                        .resolved(vin != null)
                        .lastUploadAt(a.lastCreatedAt())
                        .lastTriggerTime(trigger)
                        .systemName(a.lastSystemName())
                        .backlogDays(backlogDays)
                        .build());
            }
        }
        result.sort(BACKLOG_DAYS_DESC);
        return result;
    }

    /**
     * 已忽略车辆（填过备注）：展示备注与更新时间，供前端查看与撤销。
     */
    private static List<IgnoredVin> buildIgnoredVehicles(List<VinAggregate> aggregates,
                                                         Map<String, MxfOfflineNote> ignoredNotes) {
        if (ignoredNotes.isEmpty()) {
            return List.of();
        }
        Map<String, VinAggregate> aggByVin = new LinkedHashMap<>();
        for (VinAggregate a : aggregates) {
            aggByVin.put(a.vin(), a);
        }
        List<IgnoredVin> result = new ArrayList<>(ignoredNotes.size());
        for (Map.Entry<String, MxfOfflineNote> entry : ignoredNotes.entrySet()) {
            String vin = entry.getKey();
            MxfOfflineNote note = entry.getValue();
            VinAggregate agg = aggByVin.get(vin);
            result.add(IgnoredVin.builder()
                    .vin(vin)
                    .display(vin)
                    .resolved(isNotBlank(vin))
                    .lastUploadAt(agg == null ? null : agg.lastCreatedAt())
                    .note(note == null ? null : note.getNote())
                    .updatedAt(note == null ? null : note.getUpdatedAt())
                    .build());
        }
        result.sort(NOTE_UPDATED_DESC);
        return result;
    }

    private VinRecord toVinRecord(SliceView v) {
        return VinRecord.builder()
                .sliceId(v.slice.getId())
                .time(v.effectiveTime)
                .eventName(v.eventName)
                .eventTitle(v.eventName == null ? null : MxfSpecialEvents.titleOf(v.eventName))
                .fileName(v.slice.getName())
                .downloadable(downloadable(v.slice))
                .build();
    }

    private MxfEventRecord toEventRecord(SliceView v) {
        String vin = blankToNull(v.slice.getVin());
        return MxfEventRecord.builder()
                .eventName(v.eventName)
                .eventTitle(v.eventName == null ? null : MxfSpecialEvents.titleOf(v.eventName))
                .vin(vin)
                .vinDisplay(vin == null ? "" : vin)
                .vinResolved(vin != null)
                .triggerTime(v.effectiveTime)
                .sliceId(v.slice.getId())
                .fileName(v.slice.getName())
                .systemName(blankToNull(v.slice.getSystemName()))
                .downloadable(downloadable(v.slice))
                .build();
    }

    /**
     * 切片是否可下载原始数据包 = 非归档。
     *
     * <p>归档与否<b>不在本类推导</b>，直接委托 {@link StorageAccess#isArchived(DataSlice)}
     * （唯一来源：minio 模式 且 {@code storage_type='LOCAL'}）。归档后数据内容读取返回
     * 410 DATA_ARCHIVED，前端按钮据此置灰；真正下载走
     * {@code GET /v1/data-slices/{id}/downloads/rosbag/ticket} + {@code /native}。</p>
     */
    private boolean downloadable(DataSlice slice) {
        return slice != null && !storageAccess.isArchived(slice);
    }

    /**
     * 按天分桶生成定长趋势序列（缺失日补 0），便于前端画连续折线。
     *
     * <p>权重恒为 1（一个切片算一次）—— 左侧 VIN 曲线与中间事件曲线量纲一致，
     * 都表示「当天有多少个切片 / 多少次触发」。</p>
     */
    private static List<MxfTrendPoint> buildTrend(List<SliceView> samples, int days) {
        Map<LocalDate, Long> buckets = new LinkedHashMap<>();
        for (SliceView sample : samples) {
            LocalDateTime time = sample.effectiveTime;
            if (time == null) {
                continue;
            }
            LocalDate day = time.atZone(ZoneOffset.UTC).withZoneSameInstant(DISPLAY_ZONE).toLocalDate();
            buckets.merge(day, 1L, Long::sum);
        }
        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        List<MxfTrendPoint> points = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            points.add(MxfTrendPoint.builder()
                    .label(DAY_LABEL.format(day))
                    .date(DAY_KEY.format(day))
                    .count(buckets.getOrDefault(day, 0L))
                    .build());
        }
        return points;
    }

    /**
     * 解析右列统计日期（北京时间日期字面量）。
     *
     * <p>空 / 格式非法 / 年份越界（不在 {@code [2000, 明年]} 内）一律回退到「今天 −
     * {@code dims.mxf.vehicle.default-date-offset-days}」（默认昨天），<b>不抛异常</b>：
     * 看板是展示型接口，参数写错时给一个可解释的默认视图比返回错误页更有用。
     * 回退会打 WARN 并带上原始值，便于排查。</p>
     */
    private LocalDate resolveTargetDay(String date) {
        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        LocalDate fallback = today.minusDays(Math.max(0, config.getDefaultDateOffsetDays()));
        if (!isNotBlank(date)) {
            return fallback;
        }
        String trimmed = date.trim();
        try {
            LocalDate parsed = LocalDate.parse(trimmed, DATE_PARAM);
            if (parsed.getYear() < MIN_VALID_YEAR || parsed.getYear() > today.getYear() + 1) {
                log.warn("[MXF] 右列 date={} 年份越界，回退到 {}", trimmed, fallback);
                return fallback;
            }
            return parsed;
        } catch (DateTimeParseException e) {
            log.warn("[MXF] 右列 date={} 无法解析为 yyyy-MM-dd，回退到 {}", trimmed, fallback);
            return fallback;
        }
    }

    /**
     * 北京时间某天 00:00 对应的 <b>UTC 墙上时间</b>。
     *
     * <p>{@code data_slice} 的时间列都是 UTC 的 {@code timestamp without time zone}，
     * 所以比较前必须把「北京时间的自然日边界」换算成 UTC 字面量：
     * 北京某日 00:00 → 该日前一日 16:00（UTC）。</p>
     */
    private static LocalDateTime beijingDayStartUtc(LocalDate day) {
        return day.atStartOfDay(DISPLAY_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 日报统计窗口：自定义日期优先，否则按 preset 取<b>北京时间自然日</b>的整段。
     *
     * <p>{@code week} 用 ISO 周（周一起），与前端 {@code FilterBar} 的 {@code isoWeek} 一致；
     * {@code month} 取 1 日到下月 1 日；{@code all} 用 1970/9999 哨兵。非法 preset 按 {@code today}
     * 处理 —— 展示型接口给一个可解释的默认视图，比返回 400 更有用。</p>
     */
    private ReportRange resolveReportRange(String timeRange, String startDate, String endDate) {
        LocalDate start = parseRangeDay(startDate);
        LocalDate end = parseRangeDay(endDate);
        if (start != null && end != null) {
            if (start.isAfter(end)) {
                LocalDate tmp = start;
                start = end;
                end = tmp;
            }
            return new ReportRange("custom",
                    beijingDayStartUtc(start), beijingDayStartUtc(end.plusDays(1)));
        }

        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        String preset = timeRange == null ? "" : timeRange.trim().toLowerCase(Locale.ROOT);
        switch (preset) {
            case "week": {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return new ReportRange("week",
                        beijingDayStartUtc(monday), beijingDayStartUtc(monday.plusWeeks(1)));
            }
            case "month": {
                LocalDate first = today.withDayOfMonth(1);
                return new ReportRange("month",
                        beijingDayStartUtc(first), beijingDayStartUtc(first.plusMonths(1)));
            }
            case "all":
                return new ReportRange("all", ALL_RANGE_FROM, ALL_RANGE_TO);
            case "today":
            default:
                if (!preset.isEmpty() && !"today".equals(preset)) {
                    log.warn("[MXF] 日报 timeRange={} 无法识别，按 today 处理", timeRange);
                }
                return new ReportRange("today",
                        beijingDayStartUtc(today), beijingDayStartUtc(today.plusDays(1)));
        }
    }

    /**
     * 解析自定义日期的单侧（北京时间 {@code yyyy-MM-dd}）。
     *
     * @return 合法日期；空 / 格式非法 / 年份越界（{@code [2000, 明年]}）返回 null（视为未传）
     */
    private LocalDate parseRangeDay(String raw) {
        if (!isNotBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            LocalDate parsed = LocalDate.parse(trimmed, DATE_PARAM);
            if (parsed.getYear() < MIN_VALID_YEAR
                    || parsed.getYear() > LocalDate.now(DISPLAY_ZONE).getYear() + 1) {
                log.warn("[MXF] 日报自定义日期 {} 年份越界，忽略", trimmed);
                return null;
            }
            return parsed;
        } catch (DateTimeParseException e) {
            log.warn("[MXF] 日报自定义日期 {} 不是合法的 yyyy-MM-dd，忽略", trimmed);
            return null;
        }
    }

    /**
     * 归一化数据来源：空 / 空白 / {@code all} → null（不过滤）；大小写不敏感地对齐 MMT/ZYT/Hirain。
     *
     * <p>非法值按「不过滤」处理并打 WARN：这是展示型接口，多给数据比返回 400 更可用；
     * 但前端下拉只有这三个值，出现非法值说明调用方写错，必须留痕。</p>
     */
    private static String normalizeSourceType(String sourceType) {
        if (!isNotBlank(sourceType)) {
            return null;
        }
        String trimmed = sourceType.trim();
        if ("all".equalsIgnoreCase(trimmed)) {
            return null;
        }
        for (String allowed : SOURCE_TYPES) {
            if (allowed.equalsIgnoreCase(trimmed)) {
                return allowed;
            }
        }
        log.warn("[MXF] 未知 sourceType={}，按不过滤处理", trimmed);
        return null;
    }

    /**
     * 「过去 24H 重点事件统计」固定用的事件清单（顺序即 UI 顺序）。
     *
     * <p>直接读配置而不与 {@code camera_event_config.json} 求交：这 8 项是需求方定案的展示清单，
     * 平台新增/隐藏事件不应该静默改变看板口径。</p>
     */
    private List<String> keyEventNames() {
        List<String> configured = config.getEventOptions() == null ? List.of() : config.getEventOptions();
        List<String> names = configured.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        return names.isEmpty() ? FALLBACK_KEY_EVENTS : names;
    }

    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    /**
     * 项目标识：窗口内出现次数最多的「车型 + 来源」组合。
     *
     * <p>{@code data_slice} 没有 project 列（那在 {@code cfdi_meta} 里，且只覆盖 CFDI 来源），
     * 故按数据包列表「车型」列的同款口径取名：{@code slice_stats.vehicle_type}
     * （无统计记录时为空）拼上 {@code source_type}。全空时返回 null，前端展示「未知」。</p>
     */
    private static String resolveProject(List<SliceView> inWindow) {
        Map<String, Long> combos = new LinkedHashMap<>();
        for (SliceView v : inWindow) {
            String vehicleType = blankToNull(v.slice.getVehicleType());
            String source = blankToNull(v.slice.getSourceType());
            if (vehicleType == null && source == null) {
                continue;
            }
            String key = Stream.of(vehicleType, source)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(" "));
            combos.merge(key, 1L, Long::sum);
        }
        return combos.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // ============================================================
    // 切片视图
    // ============================================================

    private static List<SliceView> toViews(List<DataSlice> slices, LocalDateTime now) {
        List<SliceView> views = new ArrayList<>(slices.size());
        for (DataSlice s : slices) {
            views.add(new SliceView(s,
                    effectiveTime(s.getTriggerTime(), s.getStartTs(), s.getCreatedAt(), now),
                    blankToNull(s.getEventName())));
        }
        return views;
    }

    /**
     * 有效时间三级兜底：{@code trigger_time → start_ts → created_at}。
     *
     * <p>{@code data_slice.trigger_time} 要求 {@code trigger_timestamp_us} 与
     * {@code utc_diff_us} 同时存在才落库（导入侧刻意不写 1970），实测 15 条里仅 1 条有值，
     * 因此第二级 {@code start_ts} 是<b>常态路径</b>而非保险。两级都不可用时退到入库时间
     * （副作用：积压延迟退化为 0，宁可不报警也不误报）。</p>
     */
    private static LocalDateTime effectiveTime(LocalDateTime trigger,
                                               LocalDateTime startTs,
                                               LocalDateTime createdAt,
                                               LocalDateTime now) {
        if (isValidTime(trigger, now)) {
            return trigger;
        }
        if (isValidTime(startTs, now)) {
            return startTs;
        }
        return createdAt;
    }

    private static boolean isValidTime(LocalDateTime time, LocalDateTime now) {
        return time != null
                && time.getYear() >= MIN_VALID_YEAR
                && time.getYear() <= now.getYear() + 1;
    }

    /** 切片视图：原始记录 + 有效时间 + 切片级事件名（{@code data_slice.event_name}） */
    private static final class SliceView {
        final DataSlice slice;
        final LocalDateTime effectiveTime;
        final String eventName;

        SliceView(DataSlice slice, LocalDateTime effectiveTime, String eventName) {
            this.slice = slice;
            this.effectiveTime = effectiveTime;
            this.eventName = eventName;
        }

        /** 该切片是否命中指定事件（切片级单事件，命中即 1 次） */
        boolean hasEvent(String name) {
            return name != null && name.equals(eventName);
        }
    }

    /** VIN 维度聚合行（DB GROUP BY 结果，含最新切片的触发/采集起点时间与软件版本） */
    private record VinAggregate(String vin, long packetCount, LocalDateTime lastCreatedAt,
                                LocalDateTime lastTriggerTime, LocalDateTime lastStartTs,
                                String lastSystemName) {
    }

    /** 日报统计窗口（preset 仅用于回显；from/to 为 UTC 墙钟，左闭右开） */
    private record ReportRange(String preset, LocalDateTime from, LocalDateTime to) {
    }

    /**
     * 排序器集中存放。
     *
     * <p>放在这里而不是散落在各方法里：{@code Comparator.comparing} 的泛型推导在
     * 字段初始化处需要显式类型见证，集中一次写清楚比每处重复更稳。</p>
     */
    private static final Comparator<SliceView> SLICE_TIME_DESC =
            Comparator.comparing((SliceView v) -> v.effectiveTime,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<MxfEventRecord> RECORD_TIME_DESC =
            Comparator.comparing(MxfEventRecord::getTriggerTime,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<VinAggregate> AGG_LAST_CREATED_DESC =
            Comparator.comparing(VinAggregate::lastCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<AbnormalVin> BACKLOG_DAYS_DESC =
            Comparator.comparingLong(AbnormalVin::getBacklogDays).reversed();

    private static final Comparator<IgnoredVin> NOTE_UPDATED_DESC =
            Comparator.comparing(IgnoredVin::getUpdatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 可勾选事件：优先用 {@code dims.mxf.vehicle.event-options}（需求方固定的 6 个）；
     * 未配置时退化为 {@code camera_event_config.json} 的全量事件（兼容旧配置）。
     */
    private List<String> resolveEventOptions(List<String> knownNames) {
        List<String> configured = config.getEventOptions();
        if (configured == null || configured.isEmpty()) {
            return knownNames;
        }
        return configured.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private List<String> normalizeEvents(List<String> requested, Collection<String> known) {
        if (requested != null) {
            List<String> valid = requested.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && known.contains(s))
                    .distinct()
                    .toList();
            if (!valid.isEmpty()) {
                return valid;
            }
        }
        return defaultEventNames(known);
    }

    /** 配置里的默认勾选事件（过滤掉配置了但不在 camera_event_config.json 里的名字） */
    private List<String> defaultEventNames(Collection<String> known) {
        List<String> defaults = config.getDefaultEvents() == null ? List.of() : config.getDefaultEvents();
        return defaults.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> known == null || known.isEmpty() || known.contains(s))
                .distinct()
                .toList();
    }

    /**
     * 中间列筛选器选项：保持 {@code camera_event_config.json} 的 events 顺序，
     * 按 filter 名去重（一个 filter 可能对应多个 event）。
     *
     * <p>与数据浏览器「触发类型」下拉同口径：{@link FilterOptionProvider#isHiddenFilter(String)}
     * 命中的筛选器（obf_*）不展示。</p>
     */
    private List<FilterOption> buildFilterOptions() {
        Map<String, List<String>> index = cameraEventConfig.getFilterEventIndex();
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        List<FilterOption> options = new ArrayList<>(index.size());
        index.forEach((filter, names) -> {
            if (FilterOptionProvider.isHiddenFilter(filter)) {
                return;
            }
            List<String> eventNames = names == null ? List.of() : List.copyOf(names);
            options.add(FilterOption.builder()
                    .filter(filter)
                    .eventNames(eventNames)
                    .titles(eventNames.stream().map(MxfSpecialEvents::titleOf).toList())
                    .build());
        });
        return options;
    }

    /**
     * 解析中间列的筛选器选择。
     *
     * <p>优先级：{@code filter} 参数（筛选器语义，命中的是其映射的全部事件）
     * → {@code event} 参数（兼容旧调用：筛选器回填该事件所属的 filter，但过滤集合
     * 仍只含这一个事件，语义与 v2 一致）→ 第一个筛选器。</p>
     *
     * <p>{@code filter} 也可以传<b>事件名</b>：下拉里「只映射一个事件的筛选器」直接把
     * 事件名作为选中值送出（如 {@code hw_resource_monitor_filter} → {@code system_crash}，
     * 库里存的就是后者），此时按「只过滤这一个事件」处理，并原样回显到
     * {@code selected_filter}，让下拉能回填到同一项。</p>
     *
     * <p>配置加载失败（无筛选器）时退化为「按单个事件名过滤」，与旧行为一致；
     * 此时 {@code event} 为空则用配置默认勾选的第一个事件兜底。</p>
     */
    private FilterSelection resolveFilter(String filter, String event, List<FilterOption> options) {
        if (options.isEmpty()) {
            String single = isNotBlank(event)
                    ? event.trim()
                    : defaultEventNames(null).stream().findFirst().orElse(null);
            return new FilterSelection(single, single == null ? List.of() : List.of(single));
        }
        if (isNotBlank(filter)) {
            String trimmed = filter.trim();
            for (FilterOption option : options) {
                if (trimmed.equals(option.getFilter())) {
                    return new FilterSelection(option.getFilter(), option.getEventNames());
                }
            }
            for (FilterOption option : options) {
                if (option.getEventNames().contains(trimmed)) {
                    return new FilterSelection(trimmed, List.of(trimmed));
                }
            }
            log.debug("[MXF] 传入的 filter={} 既不是筛选器名也不是事件名，继续按 event / 默认筛选器回退", trimmed);
        }
        if (isNotBlank(event)) {
            String trimmed = event.trim();
            for (FilterOption option : options) {
                if (option.getEventNames().contains(trimmed)) {
                    return new FilterSelection(option.getFilter(), List.of(trimmed));
                }
            }
            log.debug("[MXF] 传入的 event={} 没有对应筛选器，回退到第一个筛选器", trimmed);
        }
        FilterOption first = options.get(0);
        return new FilterSelection(first.getFilter(), first.getEventNames());
    }

    /** 中间列当前生效的筛选器 + 实际用于过滤的 event_name 集合 */
    private record FilterSelection(String filter, List<String> eventNames) {
    }

    private static Map<String, MxfOfflineNote> indexNotes(List<MxfOfflineNote> notes) {
        Map<String, MxfOfflineNote> index = new LinkedHashMap<>();
        for (MxfOfflineNote n : notes) {
            if (n != null && isNotBlank(n.getVin())) {
                index.put(n.getVin().trim(), n);
            }
        }
        return index;
    }

    private static boolean sameVin(String left, String right) {
        return left != null && left.equals(right);
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        int v = value == null ? fallback : value;
        return Math.max(min, Math.min(max, v));
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return isNotBlank(s) ? s.trim() : null;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static LocalDateTime asDateTime(Object o) {
        if (o instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (o instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), ZoneOffset.UTC);
        }
        return null;
    }
}
