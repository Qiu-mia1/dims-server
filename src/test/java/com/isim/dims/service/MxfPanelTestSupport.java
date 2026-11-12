package com.isim.dims.service;

import com.isim.dims.config.CameraEventConfig;
import com.isim.dims.config.MxfVehicleDashboardConfig;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.MxfOfflineNote;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.impl.MxfVehicleDashboardServiceImpl;
import com.isim.dims.service.impl.StorageAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 单车看板 4 个面板单测的共享夹具。
 *
 * <p>看板按页面区域拆成 4 个查询（{@code getVinPanel} / {@code getFilterPanel} /
 * {@code getWindowPanel} / {@code getAbnormalPanel}），各自的用例分别落在
 * {@code MxfVinPanelTest} / {@code MxfFilterPanelTest} / {@code MxfWindowPanelTest} /
 * {@code MxfAbnormalPanelTest}；本类只提供 mock、配置与数据夹具。</p>
 *
 * <p><b>数据源是 {@code data_slice}</b>（2026-09-18 换源）。因此：</p>
 * <ul>
 *   <li>夹具是 {@link DataSlice}，不是 {@code MxfPacket}；</li>
 *   <li>{@code vin} 是<b>明文 VIN</b>，不再需要 {@code vin_md5_mapping} 反查，
 *       故本类没有 mapping mock；</li>
 *   <li>切片列表走自定义 SQL {@link DataSliceMapper#selectDashboardRows(int)}，
 *       VIN 聚合走 {@code selectMaps}。</li>
 * </ul>
 *
 * <p>所有时间夹具均相对 {@code now} 构造，不依赖真实时钟。</p>
 *
 * @feature: mxf
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class MxfPanelTestSupport {

    /** 明文 VIN（data_slice.vin 就是它，不再是 32 位 md5） */
    protected static final String VIN_A = "LXWMUZDC1THG05059";
    protected static final String VIN_B = "LXWMUZDC2THG05060";
    protected static final String VIN_C = "LXWMUZDC3THG05063";

    /** 需求方 2026-09-17 确定的 6 个固定重点事件（v5 /window 端点仍在用） */
    protected static final List<String> DASHBOARD_EVENTS = List.of(
            "imu_collision", "system_crash", "cp_aes_trig_event",
            "cp_ess_trig_event", "unp_tkov_colli", "fushion_aeb");

    /** 2026-10 原型定案的 8 个重点事件（v6 /key-events 与 MxfVehicleDashboardConfig 默认值） */
    protected static final List<String> KEY_EVENTS_8 = List.of(
            "imu_collision", "system_crash", "fushion_aeb", "cp_aes_trig_event",
            "cp_ess_trig_event", "meb_brake_event", "unp_tkov_colli", "dssad_collision");

    /** 一个筛选器映射两个事件（真实配置里 highbeam_on_filter 就是这种） */
    protected static final String FILTER_MULTI = "highbeam_on_filter";
    protected static final List<String> FILTER_MULTI_EVENTS = List.of("high_highbeam_on", "mid_highbeam_on");

    @Mock
    protected DataSliceMapper dataSliceMapper;

    @Mock
    protected CameraEventConfig cameraEventConfig;

    @Mock
    protected MxfOfflineNoteService offlineNoteService;

    /** 归档判定来源：mock 掉，用例只声明 storage_type，归档与否按下面的生产口径推导 */
    @Mock
    protected StorageAccess storageAccess;

    protected MxfVehicleDashboardConfig config;
    protected MxfVehicleDashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        config = new MxfVehicleDashboardConfig();
        config.setBacklogDelayDays(2);
        config.setBacklogVehicles(20);
        config.setDefaultDateOffsetDays(1);
        config.setTrendDays(3);
        config.setEventOptions(new ArrayList<>(DASHBOARD_EVENTS));
        config.setDefaultEvents(new ArrayList<>(DASHBOARD_EVENTS));
        config.setMaxPackets(5000);
        config.setMaxRecords(200);

        service = new MxfVehicleDashboardServiceImpl(
                dataSliceMapper, cameraEventConfig, offlineNoteService, config, storageAccess);

        givenArchivedRule();
        givenKnownEvents();
        givenFilters();
        givenIgnored();  // 默认无忽略车辆
    }

    /**
     * 归档判定按<b>生产口径</b>实现：归档 ⇔ minio 模式 且 {@code storage_type='LOCAL'}
     * （用例里存储模式恒为 minio，故只看 storage_type）。
     *
     * <p>夹具只声明 {@code storage_type}，归档与否由该规则推导 —— 这样若生产侧把方向写反
     * （例如误判成「MINIO 才是归档」），用例必然失败，而不是跟着一起错。
     * {@link StorageAccess#isArchived} 自身的规则由 StorageArchivedAccessHttpTest 覆盖。</p>
     */
    private void givenArchivedRule() {
        when(storageAccess.isArchived(any(DataSlice.class)))
                .thenAnswer(inv -> archivedByRule(inv.getArgument(0)));
    }

    private static boolean archivedByRule(DataSlice s) {
        return s != null && "LOCAL".equalsIgnoreCase(s.getStorageType());
    }

    // ============================================================
    // 夹具
    // ============================================================

    protected static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    // ============================================================
    // 右列窗口专用夹具（北京时间自然日 ↔ UTC 墙上时间）
    //   data_slice 的时间列是 UTC 的 timestamp without time zone，而右列的
    //   「某一天」是北京时间 —— 用例只写北京墙上时间，换算交给下面两个方法，
    //   避免每个用例各写一遍 +8/−8 而算错反向。
    // ============================================================

    /** 展示时区（右列窗口切天、趋势分桶都用它） */
    protected static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    /** 右列默认统计日 = 北京时间「昨天」（与 dims.mxf.vehicle.default-date-offset-days=1 对齐） */
    protected static final LocalDate WINDOW_DAY = LocalDate.now(DISPLAY_ZONE).minusDays(1);

    /** 北京时间某天的某整点 → 库里存的 UTC 时间 */
    protected static LocalDateTime beijingDayAt(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(DISPLAY_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** 默认统计日（昨天）的某整点 → 库里存的 UTC 时间 */
    protected static LocalDateTime windowDayAt(int hour) {
        return beijingDayAt(WINDOW_DAY, hour);
    }

    /** 简化夹具：start_ts 与 trigger_time 同值 */
    protected static DataSlice slice(String id, String vin, String eventName,
                                     LocalDateTime createdAt, LocalDateTime triggerTime) {
        return sliceFull(id, vin, eventName, createdAt, triggerTime, triggerTime);
    }

    protected static DataSlice sliceFull(String id, String vin, String eventName,
                                         LocalDateTime createdAt, LocalDateTime triggerTime,
                                         LocalDateTime startTs) {
        DataSlice s = new DataSlice();
        s.setId(id);
        s.setVin(vin);
        s.setEventName(eventName);
        s.setCreatedAt(createdAt);
        s.setTriggerTime(triggerTime);
        s.setStartTs(startTs);
        s.setName("JMC_CX835ICA_" + (vin == null ? "UNKNOWN" : vin)
                + "_MMT_cfdi-mid-pri_" + (eventName == null ? "disabled" : eventName)
                + "_2026-09-18 10:00:00");
        // 默认 minio 模式下的新导入切片：storage_type=MINIO，可读可下载
        s.setStorageType("MINIO");
        s.setSourceType("MMT");
        s.setStatus("READY");
        s.setVehicleType("CX835ICA");
        return s;
    }

    /**
     * 归档态切片：minio 模式 + {@code storage_type='LOCAL'} 的存量切片。
     *
     * <p>数据内容读取返回 410 DATA_ARCHIVED，看板应把下载按钮置灰。
     * 注意方向：<b>LOCAL 才是归档</b>（存量本地数据不迁移 MinIO、仅归档）。</p>
     */
    protected DataSlice archived(DataSlice s) {
        s.setStorageType("LOCAL");
        return s;
    }

    protected static MxfOfflineNote note(String vin, String text) {
        MxfOfflineNote n = new MxfOfflineNote();
        n.setId("note-" + vin.substring(0, 6));
        n.setVin(vin);
        n.setNote(text);
        n.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return n;
    }

    /** selectMaps 返回的聚合行（触发/采集起点时间取最后上传时间，即延迟 0） */
    protected static Map<String, Object> vinRow(String vin, long packetCount, LocalDateTime lastCreatedAt) {
        return vinRowFull(vin, packetCount, lastCreatedAt, lastCreatedAt, lastCreatedAt);
    }

    /** selectMaps 返回的聚合行：key 为 PG 列别名 */
    protected static Map<String, Object> vinRowFull(String vin, long packetCount, LocalDateTime lastCreatedAt,
                                                   LocalDateTime lastTriggerTime, LocalDateTime lastStartTs) {
        return vinRowWithSystem(vin, packetCount, lastCreatedAt, lastTriggerTime, lastStartTs, null);
    }

    /** 同上，但带上最新切片的软件版本（{@code data_slice.system_name}） */
    protected static Map<String, Object> vinRowWithSystem(String vin, long packetCount,
                                                          LocalDateTime lastCreatedAt,
                                                          LocalDateTime lastTriggerTime,
                                                          LocalDateTime lastStartTs,
                                                          String lastSystemName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("vin", vin);
        row.put("packet_count", packetCount);
        row.put("last_created_at", lastCreatedAt);
        row.put("last_trigger_time", lastTriggerTime);
        row.put("last_start_ts", lastStartTs);
        row.put("last_system_name", lastSystemName);
        return row;
    }

    protected void givenSlices(List<DataSlice> slices) {
        when(dataSliceMapper.selectDashboardRows(anyInt())).thenReturn(new ArrayList<>(slices));
    }

    @SuppressWarnings("unchecked")
    protected void givenVinAggregate(Map<String, Object>... rows) {
        when(dataSliceMapper.selectMaps(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    protected void givenIgnored(MxfOfflineNote... notes) {
        when(offlineNoteService.listAll()).thenReturn(new ArrayList<>(List.of(notes)));
    }

    /** camera_event_config.json 的全量事件（传参校验用；顺序刻意与看板 6 个不同） */
    protected void givenKnownEvents() {
        Map<String, CameraEventConfig.EventEntry> events = new LinkedHashMap<>();
        for (String name : List.of("high_highbeam_on", "imu_collision", "system_crash", "fushion_aeb",
                "cp_aes_trig_event", "cp_ess_trig_event", "unp_tkov_colli", "dssad_collision",
                "lpnp_takeover", "rads_takeover")) {
            events.put(name, new CameraEventConfig.EventEntry());
        }
        when(cameraEventConfig.getEvents()).thenReturn(Collections.unmodifiableMap(events));
    }

    /**
     * 筛选器反查索引（顺序刻意保留「第一个筛选器 = imu_collision」，与真实配置一致）。
     */
    protected void givenFilters() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        index.put("imu_collision", List.of("imu_collision"));
        index.put("hw_resource_monitor_filter", List.of("system_crash"));
        index.put(FILTER_MULTI, FILTER_MULTI_EVENTS);
        index.put("fushion_aeb", List.of("fushion_aeb"));
        index.put("cp_aes_trig_event", List.of("cp_aes_trig_event"));
        index.put("cp_ess_trig_event", List.of("cp_ess_trig_event"));
        index.put("unp_tkov_colli", List.of("unp_tkov_colli"));
        index.put("dssad_collision", List.of("dssad_collision"));
        index.put("lpnp_takeover", List.of("lpnp_takeover"));
        index.put("rads_takeover", List.of("rads_takeover"));
        when(cameraEventConfig.getFilterEventIndex()).thenReturn(Collections.unmodifiableMap(index));
    }
}
