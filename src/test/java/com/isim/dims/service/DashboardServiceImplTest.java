package com.isim.dims.service;

import com.isim.dims.dto.DashboardAlertsResponse;
import com.isim.dims.dto.DashboardDistributionResponse;
import com.isim.dims.dto.DashboardKpiResponse;
import com.isim.dims.dto.DashboardStatsResponse;
import com.isim.dims.dto.DashboardTrendsResponse;
import com.isim.dims.dto.FuncDetailResponse;
import com.isim.dims.dto.FuncPerfResponse;
import com.isim.dims.dto.SceneDistResponse;
import com.isim.dims.dto.TakeoverReasonsResponse;
import com.isim.dims.dto.Top5Response;
import com.isim.dims.dto.VehicleTypesResponse;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.SliceStats;
import com.isim.dims.mapper.CameraCalibrationMapper;
import com.isim.dims.mapper.CfdiMetaMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.mapper.SliceStatsMapper;
import com.isim.dims.service.impl.DashboardServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private CameraCalibrationMapper cameraCalibrationMapper;

    @Mock
    private SensorStreamMapper sensorStreamMapper;

    @Mock
    private CfdiMetaMapper cfdiMetaMapper;

    @Mock
    private SliceStatsMapper sliceStatsMapper;

    @InjectMocks
    private DashboardServiceImpl dashboardService;

    // ========================================================================
    // getStats()
    // ========================================================================

    @Test
    void getStats_WithData_ReturnsCorrectStats() {
        when(dataSliceMapper.selectCount(null)).thenReturn(100L);
        when(dataSliceMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
        when(dataSliceMapper.sumFileSize()).thenReturn(80_000_000_000L);
        when(dataSliceMapper.selectList(any())).thenReturn(createSlicesWithSensorTypes());

        DashboardStatsResponse resp = dashboardService.getStats();

        assertThat(resp.getTotalSlices()).isEqualTo(100);
        assertThat(resp.getTodayUploads()).isEqualTo(5);
        assertThat(resp.getTotalDataSize()).isEqualTo(80_000_000_000L);
        assertThat(resp.getSensorDistribution()).isNotEmpty();
        assertThat(resp.getUpdatedAt()).isNotNull();
    }

    @Test
    void getStats_EmptyDb_ReturnsZeroStats() {
        when(dataSliceMapper.selectCount(null)).thenReturn(0L);
        when(dataSliceMapper.selectCount(any())).thenReturn(0L);
        when(dataSliceMapper.sumFileSize()).thenReturn(null);
        when(dataSliceMapper.selectList(any())).thenReturn(Collections.emptyList());

        DashboardStatsResponse resp = dashboardService.getStats();

        assertThat(resp.getTotalSlices()).isEqualTo(0);
        assertThat(resp.getTodayUploads()).isEqualTo(0);
        assertThat(resp.getTotalDataSize()).isEqualTo(0);
        assertThat(resp.getTotalDataSizeHuman()).isEqualTo("0 B");
        // 传感器分布始终返回全部 8 个类别（计数为 0）
        assertThat(resp.getSensorDistribution()).hasSize(8);
        resp.getSensorDistribution().forEach(item -> assertThat(item.getCount()).isZero());
    }

    @Test
    void getStats_NullCounts_ReturnsZero() {
        when(dataSliceMapper.selectCount(null)).thenReturn(null);
        when(dataSliceMapper.selectCount(any())).thenReturn(null);
        when(dataSliceMapper.sumFileSize()).thenReturn(null);
        when(dataSliceMapper.selectList(any())).thenReturn(Collections.emptyList());

        DashboardStatsResponse resp = dashboardService.getStats();

        assertThat(resp.getTotalSlices()).isEqualTo(0);
        assertThat(resp.getTodayUploads()).isEqualTo(0);
    }

    // ========================================================================
    // getDistribution()
    // ========================================================================

    @Test
    void getDistribution_SourceType_ReturnsDistribution() {
        when(dataSliceMapper.selectCount(null)).thenReturn(10L);
        when(dataSliceMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("label", "MMT", "cnt", 6L),
                Map.of("label", "ZYT", "cnt", 4L)
        ));

        DashboardDistributionResponse resp = dashboardService.getDistribution("source_type");

        assertThat(resp.getDimension()).isEqualTo("source_type");
        assertThat(resp.getItems()).hasSize(2);
        assertThat(resp.getItems().get(0).getLabel()).isIn("MMT", "ZYT");
        assertThat(resp.getItems().get(0).getCount()).isPositive();
        assertThat(resp.getItems().get(0).getPercentage()).isPositive();
    }

    @Test
    void getDistribution_CfdiDimension_ReturnsDistribution() {
        when(dataSliceMapper.selectCount(null)).thenReturn(10L);
        when(dataSliceMapper.selectCfdiDistribution("project")).thenReturn(List.of(
                Map.of("label", "LUS", "cnt", 6L),
                Map.of("label", "N/A", "cnt", 4L)
        ));

        DashboardDistributionResponse resp = dashboardService.getDistribution("project");

        assertThat(resp.getDimension()).isEqualTo("project");
        assertThat(resp.getItems()).hasSize(2);
    }

    @Test
    void getDistribution_EmptyDb_ReturnsEmptyItems() {
        when(dataSliceMapper.selectCount(null)).thenReturn(0L);

        DashboardDistributionResponse resp = dashboardService.getDistribution("source_type");

        assertThat(resp.getItems()).isEmpty();
    }

    @Test
    void getDistribution_InvalidDimension_ThrowsException() {
        when(dataSliceMapper.selectCount(null)).thenReturn(10L);

        assertThrows(IllegalArgumentException.class,
                () -> dashboardService.getDistribution("invalid_dim"));
    }

    // ========================================================================
    // getAlerts()
    // ========================================================================

    @Test
    void getAlerts_WithMissingCalibration_ReturnsAlert() {
        when(dataSliceMapper.selectMissingCalibrationSliceIds()).thenReturn(
                List.of("slice-1", "slice-2")
        );
        when(dataSliceMapper.selectCount(any())).thenReturn(1L, 2L, 1L);

        DashboardAlertsResponse resp = dashboardService.getAlerts();

        assertThat(resp.getAlerts()).isNotEmpty();
    }

    @Test
    void getAlerts_NoIssues_ReturnsEmpty() {
        when(dataSliceMapper.selectMissingCalibrationSliceIds()).thenReturn(Collections.emptyList());
        when(dataSliceMapper.selectCount(any())).thenReturn(0L);

        DashboardAlertsResponse resp = dashboardService.getAlerts();

        assertThat(resp.getAlerts()).isEmpty();
    }

    // ========================================================================
    // getTrends()
    // ========================================================================

    @Test
    void getTrends_WithData_ReturnsDailyItems() {
        // 使用相对日期以实现动态时间范围测试
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate day1 = today.minusDays(6);
        LocalDate day2 = today.minusDays(5);
        String day1Str = day1.toString();
        when(dataSliceMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("day_date", java.sql.Date.valueOf(day1), "cnt", 3L, "total_bytes", 1000L),
                Map.of("day_date", java.sql.Date.valueOf(day2), "cnt", 5L, "total_bytes", 2000L)
        ));

        DashboardTrendsResponse resp = dashboardService.getTrends(7);

        assertThat(resp.getTrends()).hasSize(7);
        assertThat(resp.getTrends().get(0).getDate()).isEqualTo(day1Str);
        assertThat(resp.getTrends().get(0).getUploadCount()).isEqualTo(3);
    }

    @Test
    void getTrends_ZeroDays_DefaultsTo30() {
        when(dataSliceMapper.selectMaps(any())).thenReturn(Collections.emptyList());

        DashboardTrendsResponse resp = dashboardService.getTrends(0);

        assertThat(resp.getTrends()).hasSize(30);
        resp.getTrends().forEach(item -> {
            assertThat(item.getUploadCount()).isZero();
            assertThat(item.getTotalSizeBytes()).isZero();
        });
    }

    @Test
    void getTrends_EmptyDb_FillsWithZeros() {
        when(dataSliceMapper.selectMaps(any())).thenReturn(Collections.emptyList());

        DashboardTrendsResponse resp = dashboardService.getTrends(5);

        assertThat(resp.getTrends()).hasSize(5);
        resp.getTrends().forEach(item -> {
            assertThat(item.getUploadCount()).isZero();
            assertThat(item.getTotalSizeBytes()).isZero();
        });
    }

    // ========================================================================
    // 重构版驾驶舱：getKpi / getVehicleTypes / getSceneDist / getFuncPerf
    // ========================================================================

    @Test
    void getKpi_WithData_ReturnsFiveCards() {
        when(sliceStatsMapper.selectMaps(any())).thenReturn(List.of(Map.of(
                "mileage", 100.5,
                "noa_rate", 0.95,
                "aeb_trig", 3L,
                "aeb_scenes", 10L,
                "apa_rate", 0.8
        )));
        when(dataSliceMapper.selectCount(any())).thenReturn(7L);

        DashboardKpiResponse resp = dashboardService.getKpi(null, null, null, null, null, null, null);

        assertThat(resp.getKpiCards()).hasSize(5);
        assertThat(resp.getKpiCards())
                .anyMatch(c -> "total_mileage_km".equals(c.getKey()) && c.getValue() == 100.5)
                .anyMatch(c -> "total_slices".equals(c.getKey()) && c.getValue() == 7.0)
                .anyMatch(c -> "noa_success_rate".equals(c.getKey()) && c.getValue() == 95.0)
                .anyMatch(c -> "aeb_intervention_rate".equals(c.getKey()) && c.getValue() == 30.0)
                .anyMatch(c -> "apa_success_rate".equals(c.getKey()) && c.getValue() == 80.0);
    }

    @Test
    void getVehicleTypes_WithData_ReturnsTypes() {
        when(sliceStatsMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("vehicle_type", "L6"),
                Map.of("vehicle_type", "L7")
        ));

        VehicleTypesResponse resp = dashboardService.getVehicleTypes(null, null);

        assertThat(resp.getTypes()).hasSize(2);
        assertThat(resp.getTypes()).extracting(VehicleTypesResponse.TypeItem::getValue)
                .containsExactly("L6", "L7");
    }

    @Test
    void getSceneDist_WithData_ReturnsNormalizedScenes() {
        when(sliceStatsMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("road_scene", "URBAN", "cnt", 6L),
                Map.of("road_scene", "unknown", "cnt", 4L)   // 未匹配枚举 → 其他
        ));

        SceneDistResponse resp = dashboardService.getSceneDist(null, null, null, null, null, null, null);

        assertThat(resp.getItems()).hasSize(2);
        assertThat(resp.getItems())
                .anyMatch(i -> "URBAN".equals(i.getSceneType()) && i.getCount() == 6L)
                .anyMatch(i -> i.getSceneType() == null && "其他".equals(i.getLabel()));
    }

    @Test
    void getFuncPerf_WithData_ReturnsThreeFunctionsWithScenes() {
        when(sliceStatsMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("road_scene", "URBAN", "rate", 0.5, "cnt", 3L)
        ));

        FuncPerfResponse resp = dashboardService.getFuncPerf(null, null, null, null, null, null, null);

        assertThat(resp.getFunctions()).hasSize(3);
        FuncPerfResponse.FunctionItem noa = resp.getFunctions().stream()
                .filter(f -> "noa_takeover_rate".equals(f.getKey()))
                .findFirst().orElseThrow();
        // 5 个固定场景 + 其他；rate 0-1 → 显示层 ×100 → 50.0
        assertThat(noa.getByScene()).hasSize(6);
        assertThat(noa.getByScene().get(0).getRate()).isEqualTo(50.0);
        assertThat(noa.getByScene().get(0).getCount()).isEqualTo(3L);
    }

    // ========================================================================
    // 重构版驾驶舱：getFuncDetail / getTakeoverReasons / getTop5
    // ========================================================================

    @Test
    void getFuncDetail_NoaWithData_ReturnsCardsAndIndicators() {
        when(sliceStatsMapper.selectMaps(any())).thenReturn(List.of(Map.of(
                "noa_act", 5L,
                "aeb_trig", 2L,
                "apa_task", 1L,
                "noa_mileage", 100.0,
                "noa_dur", 600.0,
                "takeover", 3L,
                "speed", 20.0,
                "curvature", 0.01,
                "sat", 12.0,
                "hdop", 1.5
        )));

        FuncDetailResponse resp = dashboardService.getFuncDetail("NOA", null, null, null, null, null, null, null);

        assertThat(resp.getFunction()).isEqualTo("NOA");
        assertThat(resp.getKpiCards()).hasSize(4);
        // 600s → 10 min
        assertThat(resp.getKpiCards())
                .anyMatch(c -> "avg_duration_min".equals(c.getKey()) && c.getValue() == 10.0)
                .anyMatch(c -> "takeover_count".equals(c.getKey()) && c.getValue() == 3.0);
        assertThat(resp.getIndicatorCards()).hasSize(8);
        // HDOP 为无量纲精度因子，unit 不标注 "m"
        assertThat(resp.getIndicatorCards())
                .anyMatch(c -> "gnss_precision_m".equals(c.getKey()) && "".equals(c.getUnit()));
        // lane_offset_m / lane_change_status：slice_stats 暂无对应列，占位返回 0 / null
        assertThat(resp.getIndicatorCards())
                .anyMatch(c -> "lane_offset_m".equals(c.getKey()) && Double.valueOf(0.0).equals(c.getValue()))
                .anyMatch(c -> "lane_change_status".equals(c.getKey()) && c.getValue() == null);
    }

    @Test
    void getTakeoverReasons_WithData_AggregatesJsonCounts() {
        SliceStats s1 = new SliceStats();
        s1.setTakeoverReasons("{\"distraction\": 2, \"road_condition\": 1}");
        SliceStats s2 = new SliceStats();
        s2.setTakeoverReasons("{\"distraction\": 1}");
        when(sliceStatsMapper.selectList(any())).thenReturn(List.of(s1, s2));

        TakeoverReasonsResponse resp = dashboardService.getTakeoverReasons(null, null, null, null, null, null, null);

        assertThat(resp.getItems()).hasSize(2);
        TakeoverReasonsResponse.ReasonItem distraction = resp.getItems().stream()
                .filter(i -> "distraction".equals(i.getReason()))
                .findFirst().orElseThrow();
        assertThat(distraction.getCount()).isEqualTo(3L);
        assertThat(distraction.getPercentage()).isEqualTo(75.0);
    }

    @Test
    void getTop5_WithManyScenes_ReturnsOnlyTop5() {
        SliceStats stats = new SliceStats();
        stats.setSceneBreakdown(
                "{\"s1\": 1, \"s2\": 2, \"s3\": 3, \"s4\": 4, \"s5\": 5, \"s6\": 6, \"s7\": 7}");
        when(sliceStatsMapper.selectList(any())).thenReturn(List.of(stats));

        Top5Response resp = dashboardService.getTop5(null, null, null, null, null, null, null);

        // 超过 5 个场景时只返回前 5（TOP5 截断）
        assertThat(resp.getItems()).hasSize(5);
        assertThat(resp.getItems().get(0).getRank()).isEqualTo(1);
        assertThat(resp.getItems().get(0).getScenarioName()).isEqualTo("s7");
        assertThat(resp.getItems().get(0).getTriggerCount()).isEqualTo(7L);
        assertThat(resp.getItems().get(4).getRank()).isEqualTo(5);
    }

    // ========================================================================
    // helper
    // ========================================================================

    private List<DataSlice> createSlicesWithSensorTypes() {
        DataSlice s1 = new DataSlice();
        s1.setSensorTypes("[\"camera\",\"gnss_imu\"]");
        DataSlice s2 = new DataSlice();
        s2.setSensorTypes("[\"camera\",\"lidar\"]");
        return List.of(s1, s2);
    }
}
