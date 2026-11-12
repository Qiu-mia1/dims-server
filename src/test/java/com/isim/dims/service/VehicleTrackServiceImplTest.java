package com.isim.dims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.TrajectoryQueryResult;
import com.isim.dims.dto.TrajectoryResponse;
import com.isim.dims.dto.VehicleTrackResponse;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.impl.VehicleTrackServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 看板地图车辆轨迹 Service 单元测试。
 *
 * <p>覆盖：egoPoseMessages 解析（末点 = 主车最后位置）、产物缺失软失败、
 * 抽稀上限、点位缓存、hours / maxVehicles 归一化。</p>
 */
@ExtendWith(MockitoExtension.class)
class VehicleTrackServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private ReplayService replayService;

    @Mock
    private TrajectoryService trajectoryService;

    @InjectMocks
    private VehicleTrackServiceImpl vehicleTrackService;

    // ========================================================================
    // 正常链路
    // ========================================================================

    @Test
    void getVehicleTracks_ParsesEgoPoseMessagesAndLastPosition() throws Exception {
        when(dataSliceMapper.selectLatestSlicesPerVin(any(LocalDateTime.class), eq(20)))
                .thenReturn(List.of(slice("s1", "VIN0001")));
        when(replayService.getRosData("s1")).thenReturn(MAPPER.readTree("""
                {
                  "egoPoseOrigin": {"timestamp": 1, "lat": 39.9, "lon": 116.4, "x": 0.0, "y": 0.0},
                  "egoPoseMessages": [
                    {"timestamp": 1, "lat": 39.9000000, "lon": 116.4000000, "x": 0.0, "y": 0.0, "yaw": 90.0},
                    {"timestamp": 2, "lat": 39.9010000, "lon": 116.4010000},
                    {"timestamp": 3, "lon": 116.4020000},
                    {"timestamp": 4, "lat": 39.9020000, "lon": 116.4030000}
                  ]
                }
                """));

        VehicleTrackResponse resp = vehicleTrackService.getVehicleTracks(24, 20);

        assertThat(resp.getHours()).isEqualTo(24);
        assertThat(resp.getVehicleCount()).isEqualTo(1);
        assertThat(resp.getGeneratedAt()).isNotBlank();
        VehicleTrackResponse.VehicleTrack track = resp.getVehicles().get(0);
        assertThat(track.getVin()).isEqualTo("VIN0001");
        assertThat(track.getSliceId()).isEqualTo("s1");
        // 缺 lon 的帧被丢弃
        assertThat(track.getPointCount()).isEqualTo(3);
        assertThat(track.getPoints()).hasSize(3);
        assertThat(track.getDataSource()).isEqualTo("ego_pose");
        // 末点 = 主车最后位置
        assertThat(track.getLastLat()).isEqualTo(39.902);
        assertThat(track.getLastLon()).isEqualTo(116.403);
        assertThat(track.getTriggerTime()).isEqualTo("2026-09-17 10:30:00");
        assertThat(track.getSourceType()).isEqualTo("MMT");
    }

    @Test
    void getVehicleTracks_EgoPoseWithoutLatLon_FallsBackToGpsTrajectory() throws Exception {
        // 现网真实情况：/mla/egopose 只有 boot 系 x/y/yaw，无经纬度 → ego_pose 点集为空
        when(dataSliceMapper.selectLatestSlicesPerVin(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(slice("s1", "VIN0001")));
        when(replayService.getRosData("s1")).thenReturn(MAPPER.readTree("""
                {
                  "egoPoseMessages": [
                    {"timestamp": 1, "x": -482.152, "y": 14335.224, "yaw": 5.695646},
                    {"timestamp": 2, "x": -481.673, "y": 14335.272, "yaw": 5.699955}
                  ]
                }
                """));
        TrajectoryResponse gps = new TrajectoryResponse();
        gps.setTrajectory(List.of(
                point(39.9000000, 116.4000000),
                point(39.9010000, 116.4010000),
                point(39.9020000, 116.4030000)
        ));
        TrajectoryQueryResult ready = new TrajectoryQueryResult();
        ready.setStatus("READY");
        ready.setTrajectory(gps);
        when(trajectoryService.getTrajectory("s1")).thenReturn(ready);

        VehicleTrackResponse.VehicleTrack track = vehicleTrackService.getVehicleTracks(24, 20)
                .getVehicles().get(0);

        assertThat(track.getDataSource()).isEqualTo("gps_trajectory");
        assertThat(track.getPointCount()).isEqualTo(3);
        assertThat(track.getLastLat()).isEqualTo(39.902);
        assertThat(track.getLastLon()).isEqualTo(116.403);
    }

    private static TrajectoryResponse.TrajectoryPoint point(double lat, double lon) {
        TrajectoryResponse.TrajectoryPoint p = new TrajectoryResponse.TrajectoryPoint();
        p.setLat(lat);
        p.setLon(lon);
        return p;
    }

    @Test
    void getVehicleTracks_MissingRosData_SoftFailsWithEmptyPoints() {
        when(dataSliceMapper.selectLatestSlicesPerVin(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(slice("s1", "VIN0001")));
        when(replayService.getRosData("s1"))
                .thenThrow(new RuntimeException("REPLAY_DATA_NOT_FOUND: rosdata.json missing"));
        when(trajectoryService.getTrajectory("s1")).thenReturn(noGps());

        VehicleTrackResponse resp = vehicleTrackService.getVehicleTracks(24, 20);

        VehicleTrackResponse.VehicleTrack track = resp.getVehicles().get(0);
        assertThat(track.getPointCount()).isZero();
        assertThat(track.getPoints()).isEmpty();
        assertThat(track.getLastLat()).isNull();
        assertThat(track.getLastLon()).isNull();
        assertThat(track.getDataSource()).isEqualTo("none");
    }

    private static TrajectoryQueryResult noGps() {
        TrajectoryQueryResult result = new TrajectoryQueryResult();
        result.setStatus("NO_GPS_DATA");
        return result;
    }

    @Test
    void getVehicleTracks_NoVehicles_ReturnsEmptyList() {
        when(dataSliceMapper.selectLatestSlicesPerVin(any(LocalDateTime.class), anyInt()))
                .thenReturn(Collections.emptyList());

        VehicleTrackResponse resp = vehicleTrackService.getVehicleTracks(24, 20);

        assertThat(resp.getVehicleCount()).isZero();
        assertThat(resp.getVehicles()).isEmpty();
    }

    // ========================================================================
    // 抽稀 / 缓存 / 参数归一化
    // ========================================================================

    @Test
    void getVehicleTracks_DecimatesToPointLimitKeepingEnds() throws Exception {
        StringBuilder sb = new StringBuilder("{\"egoPoseMessages\":[");
        for (int i = 0; i < 800; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"lat\":").append(39.9 + i * 1e-5).append(",\"lon\":").append(116.4 + i * 1e-5).append('}');
        }
        sb.append("]}");
        when(dataSliceMapper.selectLatestSlicesPerVin(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(slice("s1", "VIN0001")));
        when(replayService.getRosData("s1")).thenReturn(MAPPER.readTree(sb.toString()));

        VehicleTrackResponse.VehicleTrack track = vehicleTrackService.getVehicleTracks(24, 20)
                .getVehicles().get(0);

        assertThat(track.getPointCount()).isEqualTo(300);
        assertThat(track.getPoints().get(0).getLat()).isEqualTo(39.9);
        assertThat(track.getLastLat()).isEqualTo(39.9 + 799 * 1e-5);
    }

    @Test
    void getVehicleTracks_SecondCallHitsPointsCache() throws Exception {
        when(dataSliceMapper.selectLatestSlicesPerVin(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(slice("s1", "VIN0001")));
        when(replayService.getRosData("s1")).thenReturn(MAPPER.readTree(
                "{\"egoPoseMessages\":[{\"lat\":39.9,\"lon\":116.4}]}"));

        vehicleTrackService.getVehicleTracks(24, 20);
        vehicleTrackService.getVehicleTracks(24, 20);

        verify(replayService, times(1)).getRosData(anyString());
    }

    @Test
    void getVehicleTracks_NormalizesHoursAndVehicleLimit() {
        when(dataSliceMapper.selectLatestSlicesPerVin(any(LocalDateTime.class), anyInt()))
                .thenReturn(Collections.emptyList());

        assertThat(vehicleTrackService.getVehicleTracks(0, 999).getHours()).isEqualTo(24);
        assertThat(vehicleTrackService.getVehicleTracks(999, 999).getHours()).isEqualTo(168);
        assertThat(vehicleTrackService.getVehicleTracks(-5, -5).getHours()).isEqualTo(24);

        // 车辆数上限 50 / 默认 20 归一化
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(dataSliceMapper, times(3))
                .selectLatestSlicesPerVin(any(LocalDateTime.class), limitCaptor.capture());
        assertThat(limitCaptor.getAllValues()).containsExactly(50, 50, 20);
    }

    private static DataSlice slice(String id, String vin) {
        DataSlice slice = new DataSlice();
        slice.setId(id);
        slice.setVin(vin);
        slice.setName("slice-" + id);
        slice.setSourceType("MMT");
        slice.setTriggerTime(LocalDateTime.of(2026, 9, 17, 2, 30));
        return slice;
    }
}