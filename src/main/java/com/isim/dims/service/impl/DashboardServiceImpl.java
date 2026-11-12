package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.DashboardAlertsResponse;
import com.isim.dims.dto.DashboardDistributionResponse;
import com.isim.dims.dto.DashboardKpiCard;
import com.isim.dims.dto.DashboardKpiResponse;
import com.isim.dims.dto.DashboardStatsResponse;
import com.isim.dims.dto.DashboardTrendsResponse;
import com.isim.dims.dto.FuncDetailResponse;
import com.isim.dims.dto.FuncPerfResponse;
import com.isim.dims.dto.RecentUploadsResponse;
import com.isim.dims.dto.SceneDistResponse;
import com.isim.dims.dto.TakeoverReasonsResponse;
import com.isim.dims.dto.Top5Response;
import com.isim.dims.dto.VehicleTypesResponse;
import com.isim.dims.entity.CameraCalibration;
import com.isim.dims.entity.CfdiMeta;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.entity.SliceStats;
import com.isim.dims.mapper.CameraCalibrationMapper;
import com.isim.dims.mapper.CfdiMetaMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.mapper.SliceStatsMapper;
import com.isim.dims.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 仪表盘 Service 实现
 *
 * 统计概览通过 SQL 聚合实时查询，无预计算。
 * 传感器分布通过逐行读取 data_slice.sensor_types JSON 字段进行 Java 端聚合。
 * @feature: dashboard
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final DataSliceMapper dataSliceMapper;
    private final CameraCalibrationMapper cameraCalibrationMapper;
    private final SensorStreamMapper sensorStreamMapper;
    private final CfdiMetaMapper cfdiMetaMapper;
    private final SliceStatsMapper sliceStatsMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 所有已知传感器类别（用于统计排列）
    private static final List<String> ALL_SENSOR_CATEGORIES = Arrays.asList(
            "camera", "lidar", "radar", "gnss_imu", "vehicle_signal", "ultrasonic", "adas", "other"
    );

    /** 场景枚举 → UI 标签映射（未匹配/null → 其他） */
    private static final Map<String, String> SCENE_LABELS = Map.of(
            "URBAN", "城市",
            "HIGHWAY", "高速",
            "TUNNEL", "隧道",
            "TOLL_GATE", "收费站",
            "ROUNDABOUT", "环路"
    );

    /** func-perf 场景固定展示顺序（缺失场景补 0，保证图表 X 轴稳定） */
    private static final List<String> SCENE_ORDER = Arrays.asList(
            "URBAN", "HIGHWAY", "TUNNEL", "TOLL_GATE", "ROUNDABOUT"
    );

    /** 功能类型白名单（func-detail） */
    private static final Set<String> FUNCTIONS = Set.of("NOA", "AEB", "APA", "LCC", "ACC");

    /** slice_stats 仅统计最近一次提取 SUCCESS 的记录（FAILED/PENDING 行可能含空值） */
    private static final String STATS_STATUS_SUCCESS = "SUCCESS";

    /** 告警类型最小传感器路数阈值 */
    private static final long MIN_SENSOR_STREAMS = 8L;

    // ========================================================================
    // 统计概览
    // ========================================================================

    @Override
    public DashboardStatsResponse getStats() {
        DashboardStatsResponse resp = new DashboardStatsResponse();

        // 1. 切片总数
        Long totalSlices = dataSliceMapper.selectCount(null);
        resp.setTotalSlices(totalSlices != null ? totalSlices : 0L);

        // 2. 今日采集数 (>= UTC 当天 00:00:00，按采集时间 start_ts 过滤)
        LocalDateTime todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        LambdaQueryWrapper<DataSlice> todayQw = new LambdaQueryWrapper<>();
        todayQw.ge(DataSlice::getStartTs, todayStart);
        Long todayUploads = dataSliceMapper.selectCount(todayQw);
        resp.setTodayUploads(todayUploads != null ? todayUploads : 0L);

        // 3. 总数据量（数据库聚合查询，避免全表加载到 Java 端）
        Long totalSizeDb = dataSliceMapper.sumFileSize();
        long totalSize = totalSizeDb != null ? totalSizeDb : 0L;
        resp.setTotalDataSize(totalSize);
        resp.setTotalDataSizeHuman(formatFileSize(totalSize));

        // 4. 传感器分布（仅 select sensor_types 列，避免全字段加载）
        List<DataSlice> allSlices = dataSliceMapper.selectList(
                new QueryWrapper<DataSlice>().select("sensor_types")
        );
        Map<String, Long> sensorCountMap = new HashMap<>();
        for (DataSlice slice : allSlices) {
            if (slice == null) continue;
            List<String> sensorTypes = parseSensorTypes(slice.getSensorTypes());
            for (String type : sensorTypes) {
                if (type != null) {
                    sensorCountMap.merge(type, 1L, Long::sum);
                }
            }
        }

        long totalWithSensor = totalSlices != null ? totalSlices : 0L;
        List<DashboardStatsResponse.SensorDistributionItem> distList = new ArrayList<>();
        for (String category : ALL_SENSOR_CATEGORIES) {
            Long count = sensorCountMap.getOrDefault(category, 0L);
            DashboardStatsResponse.SensorDistributionItem item = new DashboardStatsResponse.SensorDistributionItem();
            item.setCategory(category);
            item.setCount(count);
            item.setPercentage(totalWithSensor > 0
                    ? Math.round(count * 1000.0 / totalWithSensor) / 10.0
                    : 0.0);
            distList.add(item);
        }
        // 按数量降序排列
        distList.sort((a, b) -> Long.compare(b.getCount(), a.getCount()));
        resp.setSensorDistribution(distList);

        // 5. 更新时间
        resp.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        return resp;
    }

    // ========================================================================
    // 最近上传列表
    // ========================================================================

    @Override
    public RecentUploadsResponse getRecentUploads(int page, int pageSize) {
        // MyBatis-Plus 3.5.9 移除了 PaginationInnerInterceptor，
        // selectPage 的自动计数和 LIMIT 注入依赖该拦截器。
        // 此处使用手动分页：selectCount 取总数 + selectList 加 LIMIT/OFFSET。
        // 参数 page / pageSize 来自 Controller 层，类型为 int，无 SQL 注入风险。

        // 1. 总数
        Long total = dataSliceMapper.selectCount(null);

        // 2. 分页记录
        int offset = (page - 1) * pageSize;
        List<DataSlice> records = dataSliceMapper.selectList(
                new LambdaQueryWrapper<DataSlice>()
                        .orderByDesc(DataSlice::getCreatedAt)
                        .last("LIMIT " + pageSize + " OFFSET " + offset)
        );

        List<RecentUploadsResponse.RecentUploadItem> items = records.stream()
                .map(this::toRecentUploadItem)
                .collect(Collectors.toList());

        RecentUploadsResponse resp = new RecentUploadsResponse();
        resp.setItems(items);
        resp.setTotal(total != null ? total : 0L);
        resp.setPage(page);
        resp.setPageSize(pageSize);

        return resp;
    }

    // ========================================================================
    // 按维度分布统计
    // ========================================================================

    @Override
    public DashboardDistributionResponse getDistribution(String dimension) {
        DashboardDistributionResponse resp = new DashboardDistributionResponse();
        resp.setDimension(dimension);

        long totalCount = dataSliceMapper.selectCount(null);
        if (totalCount == 0) {
            resp.setItems(Collections.emptyList());
            return resp;
        }

        List<DashboardDistributionResponse.DistributionItem> items;
        switch (dimension) {
            case "source_type":
                items = getDistributionFromColumn("source_type");
                break;
            case "project":
                items = getCfdiDistribution("project");
                break;
            case "car_type":
                items = getCfdiDistribution("car_type");
                break;
            case "event_name":
                items = getCfdiDistribution("event_name");
                break;
            default:
                throw new IllegalArgumentException("Unsupported dimension: " + dimension);
        }

        // 计算百分比
        for (DashboardDistributionResponse.DistributionItem item : items) {
            item.setPercentage(totalCount > 0
                    ? Math.round(item.getCount() * 1000.0 / totalCount) / 10.0
                    : 0.0);
        }

        resp.setItems(items);
        return resp;
    }

    /**
     * 从 data_slice 表直接统计分布（source_type）
     */
    private List<DashboardDistributionResponse.DistributionItem> getDistributionFromColumn(String column) {
        List<DashboardDistributionResponse.DistributionItem> items = new ArrayList<>();

        // 对 source_type 做 GROUP BY 计数
        List<Map<String, Object>> rows = dataSliceMapper.selectMaps(
                new QueryWrapper<DataSlice>()
                        .select("source_type AS label, COUNT(*) AS cnt")
                        .groupBy("source_type")
        );
        for (Map<String, Object> row : rows) {
            DashboardDistributionResponse.DistributionItem item = new DashboardDistributionResponse.DistributionItem();
            item.setLabel((String) row.get("label"));
            item.setCount(((Number) row.get("cnt")).longValue());
            items.add(item);
        }
        return items;
    }

    /** 白名单：getCfdiDistribution 允许的 cfdi_meta 列名，防止 SQL 注入 */
    private static final java.util.Set<String> ALLOWED_CFDI_COLUMNS = new java.util.HashSet<>(
            java.util.Arrays.asList("car_type", "project", "event_name")
    );

    /**
     * 从 cfdi_meta 表统计分布（project / car_type / event_name）
     * 使用 LEFT JOIN 确保 COUNT 不遗漏 BAG 类型（BAG 的 cfdi_meta 为 NULL，归类为 "N/A"）
     *
     * @param column cfdi_meta 列名，限于 ALLOWED_CFDI_COLUMNS 白名单
     */
    private List<DashboardDistributionResponse.DistributionItem> getCfdiDistribution(String column) {
        // 白名单校验：拒绝白名单外的列名，防止 SQL 注入
        if (!ALLOWED_CFDI_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("Invalid cfdi_meta column: " + column
                    + ", allowed: " + ALLOWED_CFDI_COLUMNS);
        }

        List<DashboardDistributionResponse.DistributionItem> items = new ArrayList<>();

        // 使用自定义 mapper 方法进行 LEFT JOIN 分组统计
        List<Map<String, Object>> rows = dataSliceMapper.selectCfdiDistribution(column);
        for (Map<String, Object> row : rows) {
            DashboardDistributionResponse.DistributionItem item = new DashboardDistributionResponse.DistributionItem();
            Object label = row.get("label");
            item.setLabel(label != null ? label.toString() : "N/A");
            item.setCount(((Number) row.get("cnt")).longValue());
            items.add(item);
        }
        return items;
    }

    // ========================================================================
    // 质量告警
    // ========================================================================

    @Override
    public DashboardAlertsResponse getAlerts() {
        DashboardAlertsResponse resp = new DashboardAlertsResponse();
        List<DashboardAlertsResponse.AlertItem> alerts = new ArrayList<>();

        // 1. missing_calibration: CFDI 类型但 camera_calibration 表无记录
        // 使用 LEFT JOIN + GROUP BY + HAVING COUNT = 0 单次查询替代 N+1 循环
        List<String> missingCalibSliceIds = dataSliceMapper.selectMissingCalibrationSliceIds();
        if (!missingCalibSliceIds.isEmpty()) {
            DashboardAlertsResponse.AlertItem alert = new DashboardAlertsResponse.AlertItem();
            alert.setType("missing_calibration");
            alert.setLabel("标定缺失");
            alert.setCount((long) missingCalibSliceIds.size());
            alert.setSliceIds(missingCalibSliceIds);
            alerts.add(alert);
        }

        // 2. vin_empty: cfdi_meta.vin 为空或 'empty'（cfdi_meta 存在即 CFDI 链路，
        //    V19 后 source_type 为数据来源提供方，不再用于链路判别）
        long vinEmptyCount = dataSliceMapper.selectCount(
                new QueryWrapper<DataSlice>()
                        .apply("id IN (SELECT slice_id FROM cfdi_meta WHERE vin IS NULL OR vin = '' OR vin = 'empty')")
        );
        if (vinEmptyCount > 0) {
            DashboardAlertsResponse.AlertItem alert = new DashboardAlertsResponse.AlertItem();
            alert.setType("vin_empty");
            alert.setLabel("VIN 为空");
            alert.setCount(vinEmptyCount);
            alerts.add(alert);
        }

        // 3. incomplete_sensors: sensor_stream 数 < 8
        long incompleteCount = dataSliceMapper.selectCount(
                new QueryWrapper<DataSlice>()
                        .apply("(SELECT COUNT(*) FROM sensor_stream WHERE sensor_stream.slice_id = data_slice.id) < {0}", MIN_SENSOR_STREAMS)
        );
        if (incompleteCount > 0) {
            DashboardAlertsResponse.AlertItem alert = new DashboardAlertsResponse.AlertItem();
            alert.setType("incomplete_sensors");
            alert.setLabel("传感器不完整 (< 8路)");
            alert.setCount(incompleteCount);
            alerts.add(alert);
        }

        // 4. status_error: data_slice.status = 'ERROR'
        long errorCount = dataSliceMapper.selectCount(
                new LambdaQueryWrapper<DataSlice>().eq(DataSlice::getStatus, "ERROR")
        );
        if (errorCount > 0) {
            DashboardAlertsResponse.AlertItem alert = new DashboardAlertsResponse.AlertItem();
            alert.setType("status_error");
            alert.setLabel("处理失败");
            alert.setCount(errorCount);
            alerts.add(alert);
        }

        resp.setAlerts(alerts);
        return resp;
    }

    // ========================================================================
    // 时间趋势
    // ========================================================================

    @Override
    public DashboardTrendsResponse getTrends(int days) {
        if (days <= 0) days = 30;
        if (days > 365) days = 365;

        DashboardTrendsResponse resp = new DashboardTrendsResponse();
        List<DashboardTrendsResponse.TrendItem> items = new ArrayList<>();

        // 查询 N 天内按日期分组的统计
        LocalDateTime since = LocalDate.now(ZoneOffset.UTC).atStartOfDay().minusDays(days - 1);
        List<Map<String, Object>> rows = dataSliceMapper.selectMaps(
                new QueryWrapper<DataSlice>()
                        .select("DATE(start_ts) AS day_date, COUNT(*) AS cnt, SUM(file_size) AS total_bytes")
                        .ge("start_ts", since)
                        .groupBy("DATE(start_ts)")
                        .orderByAsc("day_date")
        );

        // 构建已存在的日期映射
        Map<String, Object[]> dateMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String dayDate = row.get("day_date").toString();
            dateMap.put(dayDate, new Object[]{
                    ((Number) row.get("cnt")).longValue(),
                    ((Number) row.get("total_bytes")).longValue()
            });
        }

        // 按日期顺序填充，缺失日期补 0
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = 0; i < days; i++) {
            LocalDate date = since.toLocalDate().plusDays(i);
            String dateStr = date.format(dayFmt);

            DashboardTrendsResponse.TrendItem item = new DashboardTrendsResponse.TrendItem();
            item.setDate(dateStr);

            if (dateMap.containsKey(dateStr)) {
                Object[] data = dateMap.get(dateStr);
                item.setUploadCount((Long) data[0]);
                item.setTotalSizeBytes((Long) data[1]);
            } else {
                item.setUploadCount(0L);
                item.setTotalSizeBytes(0L);
            }
            items.add(item);
        }

        resp.setTrends(items);
        return resp;
    }

    // ========================================================================
    // 重构版驾驶舱：核心 KPI
    // ========================================================================

    @Override
    public DashboardKpiResponse getKpi(String timePreset, String timeStart, String timeEnd,
                                       List<String> vehicleTypes, String sliceId,
                                       String vin, Long utcDiffUs) {
        TimeWindows windows = resolveTimeWindows(timePreset, timeStart, timeEnd);
        KpiMetrics current = computeKpiMetrics(windows.current(), vehicleTypes, sliceId, vin, utcDiffUs);
        KpiMetrics previous = windows.previous() != null
                ? computeKpiMetrics(windows.previous(), vehicleTypes, sliceId, vin, utcDiffUs)
                : null;

        List<DashboardKpiCard> cards = new ArrayList<>();
        cards.add(buildKpiCard("total_mileage_km", "采集里程(km)", round1(current.mileageKm()), "km",
                current.mileageKm(), previous != null ? previous.mileageKm() : null));
        cards.add(buildKpiCard("total_slices", "数据包数量", current.slices(), "个",
                current.slices(), previous != null ? (double) previous.slices() : null));
        cards.add(buildKpiCard("noa_success_rate", "NOA功能成功率", round1(current.noaRate()), "%",
                current.noaRate(), previous != null ? previous.noaRate() : null));
        cards.add(buildKpiCard("aeb_intervention_rate", "AEB介入率", round1(current.aebRate()), "%",
                current.aebRate(), previous != null ? previous.aebRate() : null));
        cards.add(buildKpiCard("apa_success_rate", "APA泊车成功率", round1(current.apaRate()), "%",
                current.apaRate(), previous != null ? previous.apaRate() : null));

        DashboardKpiResponse resp = new DashboardKpiResponse();
        resp.setKpiCards(cards);
        resp.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return resp;
    }

    // ========================================================================
    // 重构版驾驶舱：车型选项 / 场景分布 / 功能×场景表现
    // ========================================================================

    @Override
    public VehicleTypesResponse getVehicleTypes(String vin, Long utcDiffUs) {
        QueryWrapper<SliceStats> qw = new QueryWrapper<>();
        qw.select("DISTINCT vehicle_type")
                .isNotNull("vehicle_type")
                .ne("vehicle_type", "");
        applySliceVinUtcFilter(qw, vin, utcDiffUs);
        qw.orderByAsc("vehicle_type");
        List<Map<String, Object>> rows = sliceStatsMapper.selectMaps(qw);

        VehicleTypesResponse resp = new VehicleTypesResponse();
        List<VehicleTypesResponse.TypeItem> types = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get("vehicle_type");
            if (value == null) continue;
            VehicleTypesResponse.TypeItem item = new VehicleTypesResponse.TypeItem();
            item.setValue(value.toString());
            item.setLabel(value.toString());
            types.add(item);
        }
        resp.setTypes(types);
        return resp;
    }

    @Override
    public SceneDistResponse getSceneDist(String timePreset, String timeStart, String timeEnd,
                                          List<String> vehicleTypes, String sliceId,
                                          String vin, Long utcDiffUs) {
        TimeWindows windows = resolveTimeWindows(timePreset, timeStart, timeEnd);
        QueryWrapper<SliceStats> qw = statsFilter(windows.current(), vehicleTypes, sliceId, vin, utcDiffUs);
        qw.select("road_scene, COUNT(*) AS cnt").groupBy("road_scene");
        List<Map<String, Object>> rows = sliceStatsMapper.selectMaps(qw);

        // 未匹配枚举（null/""/未知值）合并为 "其他" 一项
        Map<String, Long> byScene = new HashMap<>();
        long total = 0;
        for (Map<String, Object> row : rows) {
            String sceneType = normalizeScene(row.get("road_scene"));
            long count = toLong(row.get("cnt"));
            byScene.merge(sceneType, count, Long::sum);
            total += count;
        }

        List<SceneDistResponse.SceneItem> items = new ArrayList<>();
        for (Map.Entry<String, Long> entry : byScene.entrySet()) {
            SceneDistResponse.SceneItem item = new SceneDistResponse.SceneItem();
            item.setSceneType(entry.getKey());
            item.setLabel(sceneLabel(entry.getKey()));
            item.setCount(entry.getValue());
            item.setPercentage(total > 0 ? Math.round(entry.getValue() * 1000.0 / total) / 10.0 : 0.0);
            items.add(item);
        }
        items.sort((a, b) -> Long.compare(b.getCount(), a.getCount()));

        SceneDistResponse resp = new SceneDistResponse();
        resp.setItems(items);
        return resp;
    }

    @Override
    public FuncPerfResponse getFuncPerf(String timePreset, String timeStart, String timeEnd,
                                        List<String> vehicleTypes, String sliceId,
                                        String vin, Long utcDiffUs) {
        TimeWindows windows = resolveTimeWindows(timePreset, timeStart, timeEnd);

        // 3 个功能 × 场景分组聚合（key: 场景枚举或 null(其他)，value: [rate, count]）
        Map<String, double[]> apa = groupedAgg(windows.current(), vehicleTypes, sliceId, vin, utcDiffUs,
                "AVG(apa_success_rate)", "COUNT(*)");
        // NOA 接管率：以 NOA 激活次数为分母 SUM(takeover_count)/SUM(noa_activation_count)，
        // 避免原 SUM(takeover_count)/COUNT(*) 语义下接管次数之和大于切片数时超过 100%；
        // NULLIF 防除零（无激活数据 → NULL → 显示层按 0 处理）。
        // ::numeric 强制小数除法（PG 整数/整数 会截断为 0/1，与 MySQL 小数除法语义不一致）。
        // SQL 返回 0-1 比例，×100 转百分比由显示层 sceneRate 统一完成（与 APA/AEB 的 rate 口径一致）。
        Map<String, double[]> noa = groupedAgg(windows.current(), vehicleTypes, sliceId, vin, utcDiffUs,
                "SUM(takeover_count)::numeric / NULLIF(SUM(noa_activation_count), 0)", "COUNT(*)");
        Map<String, double[]> aeb = groupedAgg(windows.current(), vehicleTypes, sliceId, vin, utcDiffUs,
                "SUM(aeb_trigger_count)::numeric / NULLIF(SUM(aeb_total_scenes), 0)", "COUNT(*)");

        FuncPerfResponse resp = new FuncPerfResponse();
        List<FuncPerfResponse.FunctionItem> functions = new ArrayList<>();
        functions.add(buildFunctionPerf("APA成功率", "apa_success_rate", apa));
        functions.add(buildFunctionPerf("NOA接管率", "noa_takeover_rate", noa));
        functions.add(buildFunctionPerf("AEB介入率", "aeb_intervention_rate", aeb));
        resp.setFunctions(functions);
        return resp;
    }

    // ========================================================================
    // 重构版驾驶舱：单功能详情
    // ========================================================================

    @Override
    public FuncDetailResponse getFuncDetail(String function, String timePreset, String timeStart, String timeEnd,
                                            List<String> vehicleTypes, String sliceId,
                                            String vin, Long utcDiffUs) {
        String fn = (function == null || function.isBlank()) ? "NOA" : function.trim().toUpperCase();
        if (!FUNCTIONS.contains(fn)) {
            throw new IllegalArgumentException(
                    "Unsupported function: " + function + ", allowed: NOA, AEB, APA, LCC, ACC");
        }

        TimeWindows windows = resolveTimeWindows(timePreset, timeStart, timeEnd);
        FuncDetailAgg current = computeFuncDetailAgg(windows.current(), vehicleTypes, sliceId, vin, utcDiffUs);
        FuncDetailAgg previous = windows.previous() != null
                ? computeFuncDetailAgg(windows.previous(), vehicleTypes, sliceId, vin, utcDiffUs)
                : null;

        double[] cur = selectFunctionColumns(fn, current);
        double[] prev = previous != null ? selectFunctionColumns(fn, previous) : null;

        List<DashboardKpiCard> cards = new ArrayList<>();
        cards.add(buildKpiCard("activation_count", "激活次数", cur[0], "次",
                cur[0], prev != null ? prev[0] : null));
        cards.add(buildKpiCard("mileage_km", "运行里程", round1(cur[1]), "km",
                cur[1], prev != null ? prev[1] : null));
        cards.add(buildKpiCard("avg_duration_min", "平均时长", round2(cur[2]), "min",
                cur[2], prev != null ? prev[2] : null));
        cards.add(buildKpiCard("takeover_count", "接管介入次数", cur[3], "次",
                cur[3], prev != null ? prev[3] : null));

        FuncDetailResponse resp = new FuncDetailResponse();
        resp.setFunction(fn);
        resp.setKpiCards(cards);
        resp.setIndicatorCards(buildIndicatorCards(current));
        return resp;
    }

    // ========================================================================
    // 重构版驾驶舱：接管原因分布 / 工况 TOP5
    // ========================================================================

    @Override
    public TakeoverReasonsResponse getTakeoverReasons(String timePreset, String timeStart, String timeEnd,
                                                      List<String> vehicleTypes, String sliceId,
                                                      String vin, Long utcDiffUs) {
        TimeWindows windows = resolveTimeWindows(timePreset, timeStart, timeEnd);
        QueryWrapper<SliceStats> qw = statsFilter(windows.current(), vehicleTypes, sliceId, vin, utcDiffUs);
        qw.select("takeover_reasons");

        Map<String, Long> agg = new HashMap<>();
        for (SliceStats stats : sliceStatsMapper.selectList(qw)) {
            mergeJsonCounts(stats.getTakeoverReasons(), agg);
        }

        List<Map.Entry<String, Long>> sorted = sortByCountDesc(agg);
        long total = agg.values().stream().mapToLong(Long::longValue).sum();

        List<TakeoverReasonsResponse.ReasonItem> items = new ArrayList<>();
        for (Map.Entry<String, Long> entry : sorted) {
            TakeoverReasonsResponse.ReasonItem item = new TakeoverReasonsResponse.ReasonItem();
            item.setReason(entry.getKey());
            item.setCount(entry.getValue());
            item.setPercentage(total > 0 ? Math.round(entry.getValue() * 1000.0 / total) / 10.0 : 0.0);
            items.add(item);
        }

        TakeoverReasonsResponse resp = new TakeoverReasonsResponse();
        resp.setItems(items);
        return resp;
    }

    @Override
    public Top5Response getTop5(String timePreset, String timeStart, String timeEnd,
                                List<String> vehicleTypes, String sliceId,
                                String vin, Long utcDiffUs) {
        TimeWindows windows = resolveTimeWindows(timePreset, timeStart, timeEnd);
        QueryWrapper<SliceStats> qw = statsFilter(windows.current(), vehicleTypes, sliceId, vin, utcDiffUs);
        qw.select("scene_breakdown");

        Map<String, Long> agg = new HashMap<>();
        for (SliceStats stats : sliceStatsMapper.selectList(qw)) {
            mergeJsonCounts(stats.getSceneBreakdown(), agg);
        }

        List<Top5Response.TopItem> items = new ArrayList<>();
        int rank = 1;
        // 只取排序后前 5 项（TOP5 截断），避免全量返回
        for (Map.Entry<String, Long> entry : sortByCountDesc(agg)) {
            if (rank > 5) break;
            Top5Response.TopItem item = new Top5Response.TopItem();
            item.setRank(rank++);
            item.setScenarioName(entry.getKey());
            item.setTriggerCount(entry.getValue());
            // scene_breakdown 仅含触发次数，成功率暂无数据源（后续算法补充后透传）
            item.setSuccessRate(null);
            items.add(item);
        }

        Top5Response resp = new Top5Response();
        resp.setItems(items);
        return resp;
    }

    // ========================================================================
    // 重构版驾驶舱：辅助方法
    // ========================================================================

    /** 时间窗口（start/end 均为 UTC LocalDateTime，end 含边界） */
    private record TimeWindow(LocalDateTime start, LocalDateTime end) {
    }

    /** 当前窗口 + 上一周期窗口（无时间筛选时 previous 为 null） */
    private record TimeWindows(TimeWindow current, TimeWindow previous) {
    }

    /** KPI 聚合结果 */
    private record KpiMetrics(double mileageKm, long slices, double noaRate, double aebRate, double apaRate) {
    }

    /** func-detail 聚合结果（时长已换算为分钟，其余保持 DB 单位；接管/介入为全局合计数） */
    private record FuncDetailAgg(
            // 各功能激活次数
            double noaActivation, double aebTrigger, double apaTask,
            double accActivation, double lccActivation,
            // 各功能运行里程, unit: km
            double noaMileage, double aebMileage, double apaMileage,
            double accMileage, double lccMileage,
            // 各功能平均时长, unit: min（SQL AVG(s) 后统一 → min）
            double noaDurationMin, double aebDurationMin, double apaDurationMin,
            double accDurationMin, double lccDurationMin,
            // 接管介入次数（全局：takeover_count + intervention_count）
            double takeover,
            // 通用指标
            double speedAvg, double curvatureAvg, double gnssSat, double gnssHdop,
            boolean highwayMode) {
    }

    /**
     * 解析时间筛选参数 → 当前窗口 + 上一周期窗口
     *
     * 优先级：time_start/time_end 自定义范围 > time_preset 快捷时间 > 无时间筛选。
     * 环比窗口：today → 昨天同时段；week → 上周；month → 上月；year → 去年；
     * 自定义范围 → 等长前序窗口。时间均按 UTC 处理。
     * RFC3339 解析失败仅告警并忽略该参数，不抛异常。
     */
    private TimeWindows resolveTimeWindows(String timePreset, String timeStart, String timeEnd) {
        // 1. 自定义范围优先
        OffsetDateTime startOdt = parseRfc3339(timeStart, "time_start");
        OffsetDateTime endOdt = parseRfc3339(timeEnd, "time_end");
        if (startOdt != null || endOdt != null) {
            LocalDateTime start = startOdt != null ? startOdt.toLocalDateTime() : null;
            LocalDateTime end = endOdt != null ? endOdt.toLocalDateTime() : null;
            TimeWindow current = new TimeWindow(start, end);
            TimeWindow previous = null;
            if (start != null && end != null && end.isAfter(start)) {
                Duration duration = Duration.between(start, end);
                previous = new TimeWindow(start.minus(duration), start);
            }
            return new TimeWindows(current, previous);
        }

        // 2. 快捷时间
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (timePreset != null) {
            switch (timePreset) {
                case "today": {
                    LocalDateTime dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
                    Duration elapsed = Duration.between(dayStart, now);
                    return new TimeWindows(new TimeWindow(dayStart, now),
                            new TimeWindow(dayStart.minusDays(1), dayStart.minusDays(1).plus(elapsed)));
                }
                case "week": {
                    LocalDateTime weekStart = now.with(DayOfWeek.MONDAY).toLocalDate().atStartOfDay();
                    return new TimeWindows(new TimeWindow(weekStart, now),
                            new TimeWindow(weekStart.minusWeeks(1), weekStart));
                }
                case "month": {
                    LocalDateTime monthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
                    return new TimeWindows(new TimeWindow(monthStart, now),
                            new TimeWindow(monthStart.minusMonths(1), monthStart));
                }
                case "year": {
                    LocalDateTime yearStart = now.withDayOfYear(1).toLocalDate().atStartOfDay();
                    return new TimeWindows(new TimeWindow(yearStart, now),
                            new TimeWindow(yearStart.minusYears(1), yearStart));
                }
                default:
                    log.warn("Unknown time_preset: {}, fallback to no time filter", timePreset);
            }
        }
        // 3. 无时间筛选
        return new TimeWindows(new TimeWindow(null, null), null);
    }

    /**
     * 解析 RFC3339 字符串，失败时告警并返回 null（容忍坏参数，不抛 500）
     */
    private OffsetDateTime parseRfc3339(String value, String paramName) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            log.warn("Invalid {} (RFC3339 expected): {}, ignored", paramName, value);
            return null;
        }
    }

    /**
     * 构建 slice_stats 聚合查询公共过滤器
     *
     * 始终限定 slice_stats.status='SUCCESS'（仅统计最近一次提取成功的记录）；
     * 时间过滤基于 data_slice.start_ts（切片采集开始时间，而非统计记录时间），
     * 通过子查询实现；vehicle_types 与 slice_id 直接过滤；
     * vin / utc_diff_us 通过 slice_id IN 子查询关联 data_slice（+ cfdi_meta）过滤。
     */
    private QueryWrapper<SliceStats> statsFilter(TimeWindow window, List<String> vehicleTypes, String sliceId,
                                                 String vin, Long utcDiffUs) {
        QueryWrapper<SliceStats> qw = new QueryWrapper<>();
        qw.eq("status", STATS_STATUS_SUCCESS);
        if (window != null && (window.start() != null || window.end() != null)) {
            qw.apply(buildStartTsSql(window), buildStartTsArgs(window));
        }
        if (vehicleTypes != null && !vehicleTypes.isEmpty()) {
            qw.in("vehicle_type", vehicleTypes);
        }
        if (sliceId != null && !sliceId.isEmpty()) {
            qw.eq("slice_id", sliceId);
        }
        applySliceVinUtcFilter(qw, vin, utcDiffUs);
        return qw;
    }

    /**
     * VIN / UTC 偏移过滤（作用于 slice_stats 查询，通过 slice_id IN 子查询关联 data_slice）
     *
     * vin 模糊匹配 cfdi_meta.vin 或 data_slice.name（文件名可能携带 VIN）；
     * LEFT JOIN cfdi_meta 保证 BAG（无 cfdi_meta）切片仅靠文件名也能命中。
     * utc_diff_us 精确匹配 data_slice.utc_diff_us（V18 迁移新增列，单位微秒）。
     * 两个条件均为可选项，未传时跳过；{0} 占位符由 MyBatis-Plus 绑定为 SQL 参数（防注入）。
     */
    private void applySliceVinUtcFilter(QueryWrapper<?> qw, String vin, Long utcDiffUs) {
        if (vin != null && !vin.isBlank()) {
            qw.apply("slice_id IN (SELECT ds.id FROM data_slice ds"
                            + " LEFT JOIN cfdi_meta cm ON ds.id = cm.slice_id"
                            + " WHERE (cm.vin ILIKE CONCAT('%', {0}::text, '%') OR ds.name ILIKE CONCAT('%', {0}::text, '%')))",
                    vin);
        }
        if (utcDiffUs != null) {
            qw.apply("slice_id IN (SELECT id FROM data_slice WHERE utc_diff_us = {0})", utcDiffUs);
        }
    }

    /**
     * data_slice.start_ts 时间过滤子查询 SQL（{0}/{1} 位置参数，索引按出现顺序递增；
     * 单边过滤时只使用 {0}，保证与 buildStartTsArgs 的参数个数一一对应）
     */
    private String buildStartTsSql(TimeWindow window) {
        StringBuilder sql = new StringBuilder("slice_id IN (SELECT id FROM data_slice WHERE 1=1");
        int index = 0;
        if (window.start() != null) {
            sql.append(" AND start_ts >= {").append(index++).append("}");
        }
        if (window.end() != null) {
            sql.append(" AND start_ts <= {").append(index++).append("}");
        }
        sql.append(")");
        return sql.toString();
    }

    /** 与 buildStartTsSql 对应的绑定参数 */
    private Object[] buildStartTsArgs(TimeWindow window) {
        if (window.start() != null && window.end() != null) {
            return new Object[]{window.start(), window.end()};
        }
        if (window.start() != null) {
            return new Object[]{window.start()};
        }
        return new Object[]{window.end()};
    }

    /**
     * 计算 5 个核心 KPI 的聚合值（单次 SQL 完成）
     * noa/apa 成功率 AVG 后 ×100 转为百分比；aeb 介入率 SUM/SUM ×100。
     */
    private KpiMetrics computeKpiMetrics(TimeWindow window, List<String> vehicleTypes, String sliceId,
                                         String vin, Long utcDiffUs) {
        QueryWrapper<SliceStats> qw = statsFilter(window, vehicleTypes, sliceId, vin, utcDiffUs);
        qw.select("COALESCE(SUM(vehicle_mileage_delta), 0) AS mileage",
                "COALESCE(AVG(noa_success_rate), 0) AS noa_rate",
                "COALESCE(SUM(aeb_trigger_count), 0) AS aeb_trig",
                "COALESCE(SUM(aeb_total_scenes), 0) AS aeb_scenes",
                "COALESCE(AVG(apa_success_rate), 0) AS apa_rate");
        Map<String, Object> row = sliceStatsMapper.selectMaps(qw).get(0);

        double aebScenes = toDouble(row.get("aeb_scenes"));
        double aebRate = aebScenes > 0 ? toDouble(row.get("aeb_trig")) / aebScenes : 0.0;
        return new KpiMetrics(
                toDouble(row.get("mileage")),
                countReadySlices(window, vehicleTypes, sliceId, vin, utcDiffUs),
                toDouble(row.get("noa_rate")) * 100.0,
                aebRate * 100.0,
                toDouble(row.get("apa_rate")) * 100.0);
    }

    /**
     * 数据包数量：data_slice 计数（status='READY'）
     * 车型过滤通过 EXISTS 子查询关联 slice_stats 实现；
     * vin 过滤通过 EXISTS 子查询关联 cfdi_meta（或文件名 LIKE）实现；
     * utc_diff_us 直接精确匹配 data_slice.utc_diff_us 列。
     */
    private long countReadySlices(TimeWindow window, List<String> vehicleTypes, String sliceId,
                                  String vin, Long utcDiffUs) {
        QueryWrapper<DataSlice> qw = new QueryWrapper<>();
        qw.eq("status", "READY");
        if (window != null && window.start() != null) qw.ge("start_ts", window.start());
        if (window != null && window.end() != null) qw.le("start_ts", window.end());
        if (sliceId != null && !sliceId.isEmpty()) qw.eq("id", sliceId);
        if (vehicleTypes != null && !vehicleTypes.isEmpty()) {
            StringBuilder sql = new StringBuilder(
                    "EXISTS (SELECT 1 FROM slice_stats ss WHERE ss.slice_id = data_slice.id"
                            + " AND ss.status = '" + STATS_STATUS_SUCCESS + "' AND ss.vehicle_type IN (");
            Object[] args = new Object[vehicleTypes.size()];
            for (int i = 0; i < vehicleTypes.size(); i++) {
                if (i > 0) sql.append(",");
                sql.append("{").append(i).append("}");
                args[i] = vehicleTypes.get(i);
            }
            sql.append("))");
            qw.apply(sql.toString(), args);
        }
        if (vin != null && !vin.isBlank()) {
            // 与 searchSlices 的 vin 条件一致：cfdi_meta.vin 模糊匹配 OR 文件名模糊匹配
            qw.apply("(EXISTS (SELECT 1 FROM cfdi_meta cm WHERE cm.slice_id = data_slice.id"
                            + " AND cm.vin ILIKE CONCAT('%', {0}::text, '%'))"
                            + " OR name ILIKE CONCAT('%', {0}::text, '%'))",
                    vin);
        }
        if (utcDiffUs != null) {
            qw.eq("utc_diff_us", utcDiffUs);
        }
        Long count = dataSliceMapper.selectCount(qw);
        return count != null ? count : 0L;
    }

    /**
     * 场景枚举 → UI 标签（null/""/未匹配 → 其他）
     * 注意：SCENE_LABELS 为 Map.of 不可变 Map，不接受 null key，必须先行判空。
     */
    private String sceneLabel(String sceneType) {
        if (sceneType == null) return "其他";
        return SCENE_LABELS.getOrDefault(sceneType, "其他");
    }

    /**
     * 场景值归一化：仅保留已知枚举，其余（null/""/未知）统一为 null（其他分组）
     */
    private String normalizeScene(Object scene) {
        if (scene == null) return null;
        String value = scene.toString();
        return SCENE_LABELS.containsKey(value) ? value : null;
    }

    /**
     * 按 road_scene 分组聚合（rateExpr 为聚合表达式，countExpr 为计数表达式）
     *
     * @return map: 场景枚举(或 null=其他) → [rate, count]
     */
    private Map<String, double[]> groupedAgg(TimeWindow window, List<String> vehicleTypes, String sliceId,
                                             String vin, Long utcDiffUs,
                                             String rateExpr, String countExpr) {
        QueryWrapper<SliceStats> qw = statsFilter(window, vehicleTypes, sliceId, vin, utcDiffUs);
        qw.select("road_scene", rateExpr + " AS rate", countExpr + " AS cnt").groupBy("road_scene");
        List<Map<String, Object>> rows = sliceStatsMapper.selectMaps(qw);

        Map<String, double[]> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String sceneType = normalizeScene(row.get("road_scene"));
            result.put(sceneType, new double[]{toDouble(row.get("rate")), toLong(row.get("cnt"))});
        }
        return result;
    }

    /** 组装单个功能的 by_scene 列表（固定场景顺序，缺失补 rate=0 / count=0） */
    private FuncPerfResponse.FunctionItem buildFunctionPerf(String name, String key,
                                                            Map<String, double[]> byScene) {
        FuncPerfResponse.FunctionItem function = new FuncPerfResponse.FunctionItem();
        function.setName(name);
        function.setKey(key);
        List<FuncPerfResponse.SceneRate> scenes = new ArrayList<>();
        for (String sceneType : SCENE_ORDER) {
            scenes.add(sceneRate(sceneType, byScene.get(sceneType)));
        }
        scenes.add(sceneRate(null, byScene.get(null)));
        function.setByScene(scenes);
        return function;
    }

    /** 单场景表现项（缺失数据 → rate 0 / count 0） */
    private FuncPerfResponse.SceneRate sceneRate(String sceneType, double[] data) {
        FuncPerfResponse.SceneRate rate = new FuncPerfResponse.SceneRate();
        rate.setSceneType(sceneType);
        rate.setSceneLabel(sceneLabel(sceneType));
        if (data != null) {
            rate.setRate(round1(data[0] * 100.0));
            rate.setCount((long) data[1]);
        } else {
            rate.setRate(0.0);
            rate.setCount(0L);
        }
        return rate;
    }

    /** func-detail 聚合（单次 SQL 取全部所需列；各功能激活×里程，时长 AVG(s)→min） */
    private FuncDetailAgg computeFuncDetailAgg(TimeWindow window, List<String> vehicleTypes, String sliceId,
                                               String vin, Long utcDiffUs) {
        QueryWrapper<SliceStats> qw = statsFilter(window, vehicleTypes, sliceId, vin, utcDiffUs);
        qw.select("COALESCE(SUM(noa_activation_count), 0) AS noa_act",
                "COALESCE(SUM(aeb_trigger_count), 0) AS aeb_trig",
                "COALESCE(SUM(apa_task_count), 0) AS apa_task",
                "COALESCE(SUM(acc_activation_count), 0) AS acc_act",
                "COALESCE(SUM(lcc_activation_count), 0) AS lcc_act",
                "COALESCE(SUM(noa_mileage_km), 0) AS noa_mileage",
                "COALESCE(SUM(aeb_mileage_km), 0) AS aeb_mileage",
                "COALESCE(SUM(apa_mileage_km), 0) AS apa_mileage",
                "COALESCE(SUM(acc_mileage_km), 0) AS acc_mileage",
                "COALESCE(SUM(lcc_mileage_km), 0) AS lcc_mileage",
                "COALESCE(AVG(noa_total_duration_s), 0) AS noa_dur",
                "COALESCE(AVG(aeb_total_duration_s), 0) AS aeb_dur",
                "COALESCE(AVG(apa_total_duration_s), 0) AS apa_dur",
                "COALESCE(AVG(acc_total_duration_s), 0) AS acc_dur",
                "COALESCE(AVG(lcc_total_duration_s), 0) AS lcc_dur",
                "COALESCE(SUM(COALESCE(takeover_count,0) + COALESCE(intervention_count,0)), 0) AS takeover",
                "COALESCE(AVG(speed_avg), 0) AS speed",
                "COALESCE(AVG(curvature_avg), 0) AS curvature",
                "COALESCE(AVG(gnss_sat_num_avg), 0) AS sat",
                "COALESCE(AVG(gnss_hdop_avg), 0) AS hdop",
                "COALESCE(MAX(highway_mode::int), 0) AS highway");
        Map<String, Object> row = sliceStatsMapper.selectMaps(qw).get(0);
        return new FuncDetailAgg(
                toDouble(row.get("noa_act")),
                toDouble(row.get("aeb_trig")),
                toDouble(row.get("apa_task")),
                toDouble(row.get("acc_act")),
                toDouble(row.get("lcc_act")),
                toDouble(row.get("noa_mileage")),
                toDouble(row.get("aeb_mileage")),
                toDouble(row.get("apa_mileage")),
                toDouble(row.get("acc_mileage")),
                toDouble(row.get("lcc_mileage")),
                toDouble(row.get("noa_dur")) / 60.0, // s → min
                toDouble(row.get("aeb_dur")) / 60.0,
                toDouble(row.get("apa_dur")) / 60.0,
                toDouble(row.get("acc_dur")) / 60.0,
                toDouble(row.get("lcc_dur")) / 60.0,
                toDouble(row.get("takeover")),
                toDouble(row.get("speed")),
                toDouble(row.get("curvature")),
                toDouble(row.get("sat")),
                toDouble(row.get("hdop")),
                toDouble(row.get("highway")) > 0.0);
    }

    /**
     * 按功能类型选取 4 个 KPI 聚合列
     * 五种功能（NOA/AEB/APA/ACC/LCC+）均返回 [激活次数, 运行里程, 平均时长, 接管介入次数]；
     * 其中接管介入次数复用全局 takeover+intervention 合计数（无功能维度数据源）。
     *
     * @return [activation, mileageKm, durationMin, takeover]
     */
    private double[] selectFunctionColumns(String function, FuncDetailAgg agg) {
        return switch (function) {
            case "NOA" -> new double[]{agg.noaActivation(), agg.noaMileage(), agg.noaDurationMin(), agg.takeover()};
            case "AEB" -> new double[]{agg.aebTrigger(), agg.aebMileage(), agg.aebDurationMin(), agg.takeover()};
            case "APA" -> new double[]{agg.apaTask(), agg.apaMileage(), agg.apaDurationMin(), agg.takeover()};
            case "ACC" -> new double[]{agg.accActivation(), agg.accMileage(), agg.accDurationMin(), agg.takeover()};
            case "LCC" -> new double[]{agg.lccActivation(), agg.lccMileage(), agg.lccDurationMin(), agg.takeover()};
            default -> new double[]{0.0, 0.0, 0.0, 0.0};
        };
    }

    /** 组装 8 个通用指标小卡（2×4 网格） */
    private List<FuncDetailResponse.IndicatorCard> buildIndicatorCards(FuncDetailAgg agg) {
        List<FuncDetailResponse.IndicatorCard> cards = new ArrayList<>();
        cards.add(indicatorCard("speed_avg", "车辆速度", round1(agg.speedAvg()), "m/s", null));
        cards.add(indicatorCard("curvature_avg", "弯道曲率", round2(agg.curvatureAvg()), "1/m", null));
        cards.add(indicatorCard("highway_mode", "高速优先",
                agg.highwayMode() ? "开启" : "关闭", null, agg.highwayMode() ? "good" : "normal"));
        cards.add(indicatorCard("path_planning", "路径规划", "--", null, null));
        String[] quality = gnssQuality(agg.gnssSat(), agg.gnssHdop());
        cards.add(indicatorCard("gnss_quality", "定位状态",
                quality != null ? quality[0] : "未知", null, quality != null ? quality[1] : null));
        // 导航精度：透传 HDOP 原始值（HDOP 为无量纲水平精度因子，非距离单位，unit 留空）
        cards.add(indicatorCard("gnss_precision_m", "导航精度", round2(agg.gnssHdop()), "", null));
        // lane_offset_m / lane_change_status：slice_stats 暂无对应列，占位返回 0 / null
        cards.add(indicatorCard("lane_offset_m", "车道偏移", 0.0, "m", "normal"));
        cards.add(indicatorCard("lane_change_status", "变道状态", null, null, null));
        return cards;
    }

    private FuncDetailResponse.IndicatorCard indicatorCard(String key, String title, Object value,
                                                           String unit, String status) {
        FuncDetailResponse.IndicatorCard card = new FuncDetailResponse.IndicatorCard();
        card.setKey(key);
        card.setTitle(title);
        card.setValue(value);
        card.setUnit(unit);
        card.setStatus(status);
        return card;
    }

    /**
     * 组合判断定位状态（gnss_sat_num_avg + gnss_hdop_avg）
     * good: HDOP ≤ 2 且卫星数 ≥ 8；poor: HDOP > 5 或卫星数 < 4；其余 fair；无数据 → null。
     * 阈值暂为经验值，待算法/产品确认。
     */
    private String[] gnssQuality(double sat, double hdop) {
        if (sat <= 0 && hdop <= 0) return null;
        if (hdop > 0 && hdop <= 2.0 && sat >= 8) return new String[]{"良好", "good"};
        if (hdop > 5.0 || (sat > 0 && sat < 4)) return new String[]{"差", "poor"};
        return new String[]{"一般", "fair"};
    }

    /**
     * 构建 KPI 卡片（含环比：相对上一周期差值百分比，保留 1 位小数；
     * 上一周期为 0 或无上一周期 → trend 为 null）
     */
    private DashboardKpiCard buildKpiCard(String key, String title, double value, String unit,
                                          double current, Double previous) {
        DashboardKpiCard card = new DashboardKpiCard();
        card.setKey(key);
        card.setTitle(title);
        card.setValue(value);
        card.setUnit(unit);
        if (previous != null && previous != 0.0) {
            double pct = Math.round((current - previous) / previous * 1000.0) / 10.0;
            card.setTrendPct(pct);
            card.setTrendDirection(pct > 0 ? "up" : (pct < 0 ? "down" : null));
        }
        return card;
    }

    /** JSON 字符串（{key: count}）解析后合并到聚合 Map；解析失败仅告警不中断 */
    private void mergeJsonCounts(String json, Map<String, Long> agg) {
        if (json == null || json.isBlank()) return;
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() instanceof Number number) {
                    agg.merge(entry.getKey(), number.longValue(), Long::sum);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse stats JSON column: {}", json, e);
        }
    }

    /** 按 count 降序排序（相同 count 按 key 字典序，保证稳定输出） */
    private List<Map.Entry<String, Long>> sortByCountDesc(Map<String, Long> agg) {
        return agg.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toList());
    }

    /** Object → double（null 安全） */
    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /** Object → long（null 安全） */
    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 保留 1 位小数 */
    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 保留 2 位小数 */
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * DataSlice → RecentUploadItem 转换
     */
    private RecentUploadsResponse.RecentUploadItem toRecentUploadItem(DataSlice entity) {
        RecentUploadsResponse.RecentUploadItem item = new RecentUploadsResponse.RecentUploadItem();
        item.setId(entity.getId());
        item.setName(entity.getName());
        item.setFileSize(entity.getFileSize());
        item.setFileSizeHuman(formatFileSize(entity.getFileSize()));
        item.setDurationMs(entity.getDurationMs());
        item.setSensorTypes(parseSensorTypes(entity.getSensorTypes()));
        item.setStatus(entity.getStatus());
        item.setCreatedAt(formatTime(entity.getCreatedAt()));
        return item;
    }

    /**
     * sensor_types JSON 字符串 → List 反序列化
     *
     * 过滤 null 元素（DB 中 sensor_types 数组可能包含 null，如 ["camera", null]），
     * 避免下游 merge 时因 null key 导致 NPE。
     */
    private List<String> parseSensorTypes(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return new ArrayList<>();
        }
        try {
            List<String> types = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return types.stream().filter(Objects::nonNull).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to parse sensor_types JSON: {}", json, e);
            return new ArrayList<>();
        }
    }

    /**
     * 字节数 → 人类可读格式
     */
    private String formatFileSize(Long bytes) {
        if (bytes == null || bytes == 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = (int) (Math.log10(bytes) / 3);
        double value = bytes / Math.pow(1000, unitIndex);
        return String.format("%.2f %s", value, units[Math.min(unitIndex, units.length - 1)]);
    }

    /**
     * LocalDateTime → RFC3339 字符串
     */
    private String formatTime(LocalDateTime dt) {
        if (dt == null) return null;
        return dt.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
