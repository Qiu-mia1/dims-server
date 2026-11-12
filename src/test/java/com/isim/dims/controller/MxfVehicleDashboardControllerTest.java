package com.isim.dims.controller;

import com.isim.dims.dto.MxfAbnormalPanelResponse;
import com.isim.dims.dto.MxfDailyReportResponse;
import com.isim.dims.dto.MxfEventRecord;
import com.isim.dims.dto.MxfFilterPanelResponse;
import com.isim.dims.dto.MxfKeyEventsPanelResponse;
import com.isim.dims.dto.MxfTrendPoint;
import com.isim.dims.dto.MxfVinPanelResponse;
import com.isim.dims.dto.MxfWindowPanelResponse;
import com.isim.dims.mapper.MxfPacketMapper;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.MxfDashboardService;
import com.isim.dims.service.MxfImportService;
import com.isim.dims.service.MxfOfflineNoteService;
import com.isim.dims.service.MxfTrajectoryService;
import com.isim.dims.service.MxfVehicleDashboardService;
import com.isim.dims.service.VehicleTrackService;
import com.isim.dims.service.impl.StorageAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 单车看板 4 个分区域端点的路由 / 参数绑定 / JSON 契约测试。
 *
 * <p>拆接口最大的风险是<b>路径或参数名写错但没人发现</b>：服务层单测覆盖不到
 * {@code @GetMapping} 的路径、{@code @RequestParam} 的名字，而这些恰好是前端唯一依赖的契约。
 * 本类用 {@code @WebMvcTest} 切片锁住它们（不连数据库）。</p>
 *
 * <p>字段名断言走全局 Jackson SNAKE_CASE（来自 {@code application.yml}），
 * 与前端取值路径（{@code res.selected_vin} 等）一致。</p>
 *
 * @feature: mxf
 */
@WebMvcTest(MxfController.class)
@Import(SliceSecurityTestConfig.class)
class MxfVehicleDashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MxfImportService importService;

    @MockBean
    private MxfDashboardService dashboardService;

    @MockBean
    private VehicleTrackService vehicleTrackService;

    @MockBean
    private MxfVehicleDashboardService vehicleDashboardService;

    /**
     * MxfController 在「轨迹监控」端点引入了该依赖（上游提交 1bbda1e 一带），
     * 本切片测试只关心 vehicle-dashboard 端点，mock 掉即可；不补会导致整个
     * ApplicationContext 起不来（22 条用例全 Error，不是断言失败）。
     */
    @MockBean
    private MxfTrajectoryService trajectoryService;

    @MockBean
    private MxfOfflineNoteService offlineNoteService;

    @MockBean
    private MxfPacketMapper packetMapper;

    @MockBean
    private StorageAccess storageAccess;

    // ============================================================
    // 1. 四个端点都要在，且参数原样落到服务层
    // ============================================================

    @Test
    @DisplayName("左上 /vin：路由存在，vin / trendDays / recordLimit 原样传给服务")
    void vin路由与参数绑定() throws Exception {
        givenVinPanel();
        String vin = "31a877fbe5e199cdbbdad402687d7715";

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/vin")
                        .param("vin", vin)
                        .param("trendDays", "7")
                        .param("recordLimit", "50"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getVinPanel(vin, 7, 50);
    }

    @Test
    @DisplayName("左上 /vin：不传参时三个入参都是 null（由服务层取默认值）")
    void vin路由无参() throws Exception {
        givenVinPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/vin"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getVinPanel(null, null, null);
    }

    @Test
    @DisplayName("中上 /filter：filter 参数落到服务层的筛选器入参")
    void filter路由与参数绑定() throws Exception {
        givenFilterPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/filter")
                        .param("filter", "highbeam_on_filter")
                        .param("trendDays", "7"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getFilterPanel("highbeam_on_filter", null, 7, null);
    }

    @Test
    @DisplayName("中上 /filter：旧 event 参数仍可传入（兼容，filter 优先由服务层保证）")
    void filter路由兼容event参数() throws Exception {
        givenFilterPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/filter")
                        .param("event", "mid_highbeam_on"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getFilterPanel(null, "mid_highbeam_on", null, null);
    }

    @Test
    @DisplayName("右列 /window：events 逗号串被拆成列表（空段丢弃），date 原样透传给服务层")
    void window路由拆分events() throws Exception {
        givenWindowPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/window")
                        .param("events", "imu_collision, system_crash ,,fushion_aeb,")
                        .param("date", "2026-09-22"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getWindowPanel(
                List.of("imu_collision", "system_crash", "fushion_aeb"), "2026-09-22", null);
    }

    @Test
    @DisplayName("右列 /window：不传 events 时为 null（由服务层回填默认勾选）")
    void window路由无参() throws Exception {
        givenWindowPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/window"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getWindowPanel(null, null, null);
    }

    @Test
    @DisplayName("左下 /abnormal：无查询参数时 sourceType 传 null（= 不过滤，向后兼容）")
    void abnormal路由无参() throws Exception {
        givenAbnormalPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/abnormal"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getAbnormalPanel((String) null);
    }

    @Test
    @DisplayName("左下 /abnormal：sourceType 原样落到服务层（项目下拉联动积压卡）")
    void abnormal路由带来源() throws Exception {
        givenAbnormalPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/abnormal").param("sourceType", "ZYT"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getAbnormalPanel("ZYT");
    }

    // ============================================================
    // 1.1 v6 运营日报端点
    // ============================================================

    @Test
    @DisplayName("v6 /daily-report：sourceType + timeRange 原样传给服务（自定义日期为空时不传）")
    void dailyReport路由与参数绑定() throws Exception {
        givenDailyReport();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/daily-report")
                        .param("sourceType", "ZYT")
                        .param("timeRange", "week"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getDailyReport("ZYT", "week", null, null);
    }

    @Test
    @DisplayName("v6 /daily-report：自定义起止日期原样透传")
    void dailyReport路由自定义日期() throws Exception {
        givenDailyReport();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/daily-report")
                        .param("timeRange", "custom")
                        .param("startDate", "2026-09-23")
                        .param("endDate", "2026-09-29"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getDailyReport(null, "custom", "2026-09-23", "2026-09-29");
    }

    @Test
    @DisplayName("v6 /key-events：sourceType + recordLimit + 时间参数原样传给服务")
    void keyEvents路由与参数绑定() throws Exception {
        givenKeyEvents();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/key-events")
                        .param("sourceType", "Hirain")
                        .param("recordLimit", "50")
                        .param("timeRange", "week"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getKeyEvents("Hirain", 50, "week", null, null);
    }

    @Test
    @DisplayName("v6 /key-events：自定义起止日期原样透传")
    void keyEvents路由自定义日期() throws Exception {
        givenKeyEvents();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/key-events")
                        .param("timeRange", "custom")
                        .param("startDate", "2026-09-23")
                        .param("endDate", "2026-09-29"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService)
                .getKeyEvents(null, null, "custom", "2026-09-23", "2026-09-29");
    }

    @Test
    @DisplayName("v6 /key-events：不传任何时间参数 → 5 个入参全 null（锁死「没有 defaultValue」）")
    void keyEvents路由无参() throws Exception {
        givenKeyEvents();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/key-events"))
                .andExpect(status().isOk());

        verify(vehicleDashboardService).getKeyEvents(null, null, null, null, null);
    }

    @Test
    @DisplayName("v6 /key-events/export：参数透传 + xlsx 响应头（附件下载、中文文件名）")
    void keyEvents导出路由() throws Exception {
        when(vehicleDashboardService.exportKeyEventsXlsx(any(), any(), any(), any(), any(), any()))
                .thenReturn(new byte[] {1, 2, 3});

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/key-events/export")
                        .param("sourceType", "ZYT")
                        .param("timeRange", "week")
                        .param("event", "unp_tkov_colli"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));

        verify(vehicleDashboardService)
                .exportKeyEventsXlsx("ZYT", null, "week", null, null, "unp_tkov_colli");
    }

    // ============================================================
    // 2. 旧聚合路由已删除
    // ============================================================

    @Test
    @DisplayName("旧聚合路由 GET /vehicle-dashboard 已删除 → 404")
    void 旧聚合路由已删除() throws Exception {
        mockMvc.perform(get("/v1/mxf/vehicle-dashboard"))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // 3. JSON 字段名（SNAKE_CASE）——前端直接吃这些 key
    // ============================================================

    @Test
    @DisplayName("左上 /vin 响应字段名：vin_options / selected_vin / vin_trend / vin_records / trend_days")
    void vin响应字段名() throws Exception {
        givenVinPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/vin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generated_at").exists())
                .andExpect(jsonPath("$.trend_days").value(7))
                .andExpect(jsonPath("$.vin_options").isArray())
                .andExpect(jsonPath("$.selected_vin.vin").value("abc"))
                .andExpect(jsonPath("$.selected_vin.display").value("LXWMUZDC1THG05059"))
                .andExpect(jsonPath("$.selected_vin.resolved").value(true))
                .andExpect(jsonPath("$.selected_vin.packet_count").value(3))
                .andExpect(jsonPath("$.vin_trend[0].label").value("09-18"))
                .andExpect(jsonPath("$.vin_trend[0].count").value(2))
                .andExpect(jsonPath("$.vin_records[0].slice_id").value("p1"))
                .andExpect(jsonPath("$.vin_records[0].event_name").value("unp_tkov_colli"))
                .andExpect(jsonPath("$.vin_records[0].downloadable").value(true));
    }

    @Test
    @DisplayName("中上 /filter 响应字段名：filter_options / selected_filter / selected_filter_events / event_records")
    void filter响应字段名() throws Exception {
        givenFilterPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/filter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trend_days").value(7))
                .andExpect(jsonPath("$.filter_options[0].filter").value("highbeam_on_filter"))
                .andExpect(jsonPath("$.filter_options[0].event_names").isArray())
                .andExpect(jsonPath("$.selected_filter").value("highbeam_on_filter"))
                .andExpect(jsonPath("$.selected_filter_events[0]").value("high_highbeam_on"))
                .andExpect(jsonPath("$.event_trend").isArray())
                .andExpect(jsonPath("$.event_records[0].event_name").value("high_highbeam_on"))
                .andExpect(jsonPath("$.event_records[0].vin_display").value("VIN-A"))
                .andExpect(jsonPath("$.event_records[0].vin_resolved").value(true))
                .andExpect(jsonPath("$.event_records[0].trigger_time").exists());
    }

    @Test
    @DisplayName("右列 /window 响应字段名：window（含 date）/ event_options / selected_events / window_stats / window_records")
    void window响应字段名() throws Exception {
        givenWindowPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/window"))
                .andExpect(status().isOk())
                // 统计粒度是自然日：恒定 24 小时，且必须带上「是哪一天」
                .andExpect(jsonPath("$.window.date").value("2026-09-17"))
                .andExpect(jsonPath("$.window.hours").value(24))
                .andExpect(jsonPath("$.window.start").exists())
                .andExpect(jsonPath("$.window.end").exists())
                .andExpect(jsonPath("$.event_options[0].name").value("imu_collision"))
                .andExpect(jsonPath("$.event_options[0].selected").value(true))
                .andExpect(jsonPath("$.selected_events[0]").value("imu_collision"))
                .andExpect(jsonPath("$.window_stats.total_vehicles").value(9))
                .andExpect(jsonPath("$.window_stats.total_triggers").value(4))
                .andExpect(jsonPath("$.window_stats.project").value("CX835ICA MMT"))
                .andExpect(jsonPath("$.window_stats.by_event[0].event_name").value("imu_collision"))
                .andExpect(jsonPath("$.window_stats.by_event[0].count").value(4))
                .andExpect(jsonPath("$.window_records[0].slice_id").value("p9"));
    }

    @Test
    @DisplayName("左下 /abnormal 响应字段名：thresholds / abnormal_vehicles / ignored_vehicles")
    void abnormal响应字段名() throws Exception {
        givenAbnormalPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/abnormal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholds.backlog_delay_days").value(2))
                .andExpect(jsonPath("$.thresholds.backlog_vehicles").value(20))
                .andExpect(jsonPath("$.thresholds.abnormal_count").value(1))
                .andExpect(jsonPath("$.thresholds.backlog_exceeded").value(false))
                .andExpect(jsonPath("$.thresholds.ignored_count").value(1))
                .andExpect(jsonPath("$.abnormal_vehicles[0].vin").value("abc"))
                .andExpect(jsonPath("$.abnormal_vehicles[0].display").value("VIN-A"))
                .andExpect(jsonPath("$.abnormal_vehicles[0].last_upload_at").exists())
                .andExpect(jsonPath("$.abnormal_vehicles[0].last_trigger_time").exists())
                .andExpect(jsonPath("$.abnormal_vehicles[0].backlog_days").value(7))
                .andExpect(jsonPath("$.abnormal_vehicles[0].system_name").value("V2.3.1"))
                .andExpect(jsonPath("$.ignored_vehicles[0].note").value("返厂"));
    }

    @Test
    @DisplayName("v6 /daily-report 响应字段名：range / summary / event_distribution / unclassified_count")
    void dailyReport响应字段名() throws Exception {
        givenDailyReport();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/daily-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generated_at").exists())
                .andExpect(jsonPath("$.range.preset").value("today"))
                .andExpect(jsonPath("$.range.start").exists())
                .andExpect(jsonPath("$.range.end").exists())
                .andExpect(jsonPath("$.summary.total_vehicles").value(24))
                .andExpect(jsonPath("$.summary.total_triggers").value(517))
                .andExpect(jsonPath("$.summary.total_files").value(1873))
                .andExpect(jsonPath("$.summary.total_bytes").value(6420000000000L))
                .andExpect(jsonPath("$.event_distribution[0].key").value("imu_collision"))
                .andExpect(jsonPath("$.event_distribution[0].title").value("IMU碰撞风险"))
                .andExpect(jsonPath("$.event_distribution[0].count").value(7))
                .andExpect(jsonPath("$.unclassified_count").value(3));
    }

    @Test
    @DisplayName("v6 /key-events 响应字段名：window(无 date) / events / records(含 system_name)")
    void keyEvents响应字段名() throws Exception {
        givenKeyEvents();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/key-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.preset").value("rolling24h"))
                .andExpect(jsonPath("$.window.hours").value(24))
                .andExpect(jsonPath("$.window.date").doesNotExist())
                .andExpect(jsonPath("$.events[0].event_name").value("imu_collision"))
                .andExpect(jsonPath("$.events[0].title").value("IMU碰撞风险"))
                .andExpect(jsonPath("$.events[0].count").value(3))
                .andExpect(jsonPath("$.records[0].slice_id").value("p9"))
                .andExpect(jsonPath("$.records[0].system_name").value("V2.3.0"));
    }

    @Test
    @DisplayName("四个面板响应互不包含他人字段（拆分的直接体现）")
    void 各面板响应只含自己字段() throws Exception {
        givenVinPanel();
        givenWindowPanel();
        givenAbnormalPanel();

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/vin"))
                .andExpect(jsonPath("$.window_stats").doesNotExist())
                .andExpect(jsonPath("$.abnormal_vehicles").doesNotExist())
                .andExpect(jsonPath("$.filter_options").doesNotExist());

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/window"))
                .andExpect(jsonPath("$.vin_records").doesNotExist())
                .andExpect(jsonPath("$.thresholds").doesNotExist())
                .andExpect(jsonPath("$.selected_vin").doesNotExist());

        mockMvc.perform(get("/v1/mxf/vehicle-dashboard/abnormal"))
                .andExpect(jsonPath("$.vin_options").doesNotExist())
                .andExpect(jsonPath("$.event_options").doesNotExist())
                .andExpect(jsonPath("$.window").doesNotExist());
    }

    // ============================================================
    // 夹具
    // ============================================================

    private void givenVinPanel() {
        when(vehicleDashboardService.getVinPanel(any(), any(), any()))
                .thenReturn(MxfVinPanelResponse.builder()
                        .generatedAt(LocalDateTime.of(2026, 9, 18, 5, 0))
                        .trendDays(7)
                        .vinOptions(List.of())
                        .selectedVin(MxfVinPanelResponse.VinOption.builder()
                                .vin("abc").display("LXWMUZDC1THG05059")
                                .resolved(true).packetCount(3)
                                .lastUploadAt(LocalDateTime.of(2026, 9, 18, 1, 0))
                                .build())
                        .vinTrend(List.of(MxfTrendPoint.builder()
                                .label("09-18").date("2026-09-18").count(2).build()))
                        .vinRecords(List.of(MxfVinPanelResponse.VinRecord.builder()
                                .sliceId("p1").time(LocalDateTime.of(2026, 9, 18, 1, 0))
                                .eventName("unp_tkov_colli").eventTitle("接管碰撞")
                                .fileName("a.tar.gz").downloadable(true)
                                .build()))
                        .build());
    }

    private void givenFilterPanel() {
        when(vehicleDashboardService.getFilterPanel(any(), any(), any(), any()))
                .thenReturn(MxfFilterPanelResponse.builder()
                        .generatedAt(LocalDateTime.of(2026, 9, 18, 5, 0))
                        .trendDays(7)
                        .filterOptions(List.of(MxfFilterPanelResponse.FilterOption.builder()
                                .filter("highbeam_on_filter")
                                .eventNames(List.of("high_highbeam_on", "mid_highbeam_on"))
                                .titles(List.of("high_highbeam_on", "mid_highbeam_on"))
                                .build()))
                        .selectedFilter("highbeam_on_filter")
                        .selectedFilterEvents(List.of("high_highbeam_on"))
                        .selectedEvent("high_highbeam_on")
                        .eventTrend(List.of(MxfTrendPoint.builder()
                                .label("09-18").date("2026-09-18").count(1).build()))
                        .eventRecords(List.of(MxfEventRecord.builder()
                                .eventName("high_highbeam_on").vin("abc").vinDisplay("VIN-A")
                                .vinResolved(true).triggerTime(LocalDateTime.of(2026, 9, 18, 1, 0))
                                .sliceId("p2").fileName("b.tar.gz").downloadable(true)
                                .build()))
                        .build());
    }

    private void givenWindowPanel() {
        when(vehicleDashboardService.getWindowPanel(any(), any(), any()))
                .thenReturn(MxfWindowPanelResponse.builder()
                        .generatedAt(LocalDateTime.of(2026, 9, 18, 5, 0))
                        .window(MxfWindowPanelResponse.Window.builder()
                                // 北京时间 2026-09-17 全天 → UTC [09-16 16:00, 09-17 16:00)
                                .start(LocalDateTime.of(2026, 9, 16, 16, 0))
                                .end(LocalDateTime.of(2026, 9, 17, 16, 0))
                                .date(LocalDate.of(2026, 9, 17))
                                .hours(24)
                                .build())
                        .eventOptions(List.of(MxfWindowPanelResponse.EventOption.builder()
                                .name("imu_collision").title("IMU 碰撞").selected(true).build()))
                        .selectedEvents(List.of("imu_collision"))
                        .windowStats(MxfWindowPanelResponse.WindowStats.builder()
                                .totalVehicles(9).totalTriggers(4).project("CX835ICA MMT")
                                .byEvent(List.of(MxfWindowPanelResponse.EventCount.builder()
                                        .eventName("imu_collision").title("IMU 碰撞").count(4).build()))
                                .build())
                        .windowRecords(List.of(MxfEventRecord.builder()
                                .eventName("imu_collision").vin("abc").vinDisplay("VIN-A")
                                .vinResolved(true).triggerTime(LocalDateTime.of(2026, 9, 18, 4, 0))
                                .sliceId("p9").fileName("c.tar.gz").downloadable(true)
                                .build()))
                        .build());
    }

    private void givenAbnormalPanel() {
        // v6：Controller 走的是 getAbnormalPanel(String)（sourceType 可为 null）
        when(vehicleDashboardService.getAbnormalPanel(any()))
                .thenReturn(MxfAbnormalPanelResponse.builder()
                        .generatedAt(LocalDateTime.of(2026, 9, 18, 5, 0))
                        .thresholds(MxfAbnormalPanelResponse.Thresholds.builder()
                                .backlogDelayDays(2).backlogVehicles(20)
                                .abnormalCount(1).backlogExceeded(false).ignoredCount(1)
                                .build())
                        .abnormalVehicles(List.of(MxfAbnormalPanelResponse.AbnormalVin.builder()
                                .vin("abc").display("VIN-A").resolved(true)
                                .lastUploadAt(LocalDateTime.of(2026, 9, 18, 1, 0))
                                .lastTriggerTime(LocalDateTime.of(2026, 9, 11, 1, 0))
                                .systemName("V2.3.1")
                                .backlogDays(7)
                                .build()))
                        .ignoredVehicles(List.of(MxfAbnormalPanelResponse.IgnoredVin.builder()
                                .vin("def").display("VIN-B").resolved(true)
                                .note("返厂")
                                .updatedAt(LocalDateTime.of(2026, 9, 18, 2, 0))
                                .build()))
                        .build());
    }

    private void givenDailyReport() {
        when(vehicleDashboardService.getDailyReport(any(), any(), any(), any()))
                .thenReturn(MxfDailyReportResponse.builder()
                        .generatedAt(LocalDateTime.of(2026, 9, 30, 2, 0))
                        .range(MxfDailyReportResponse.Range.builder()
                                .preset("today")
                                .start(LocalDateTime.of(2026, 9, 28, 16, 0))
                                .end(LocalDateTime.of(2026, 9, 29, 16, 0))
                                .build())
                        .summary(MxfDailyReportResponse.Summary.builder()
                                .totalVehicles(24).totalTriggers(517)
                                .totalFiles(1873).totalBytes(6_420_000_000_000L)
                                .build())
                        .eventDistribution(List.of(
                                MxfDailyReportResponse.EventBucket.builder()
                                        .key("imu_collision").title("IMU碰撞风险").count(7).build(),
                                MxfDailyReportResponse.EventBucket.builder()
                                        .key("system_crash").title("系统崩溃").count(3).build()))
                        .unclassifiedCount(3)
                        .build());
    }

    private void givenKeyEvents() {
        // v6 起是 5 参（sourceType / recordLimit / timeRange / startDate / endDate）
        when(vehicleDashboardService.getKeyEvents(any(), any(), any(), any(), any()))
                .thenReturn(MxfKeyEventsPanelResponse.builder()
                        .generatedAt(LocalDateTime.of(2026, 9, 30, 2, 0))
                        .window(MxfKeyEventsPanelResponse.Window.builder()
                                .preset("rolling24h")
                                .start(LocalDateTime.of(2026, 9, 29, 2, 0))
                                .end(LocalDateTime.of(2026, 9, 30, 2, 0))
                                .hours(24)
                                .build())
                        .events(List.of(MxfKeyEventsPanelResponse.EventCount.builder()
                                .eventName("imu_collision").title("IMU碰撞风险").count(3).build()))
                        .records(List.of(MxfEventRecord.builder()
                                .eventName("imu_collision").vin("abc").vinDisplay("VIN-A")
                                .vinResolved(true).triggerTime(LocalDateTime.of(2026, 9, 30, 1, 0))
                                .sliceId("p9").fileName("c.tar.gz")
                                .systemName("V2.3.0").downloadable(true)
                                .build()))
                        .build());
    }
}
