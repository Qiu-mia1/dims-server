package com.isim.dims.service;

import com.isim.dims.dto.SensorCalibrationRequest;
import com.isim.dims.dto.SensorCalibrationResponse;
import com.isim.dims.dto.SensorCalibrationSet;
import com.isim.dims.entity.CameraCalibration;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.SensorCalibration;
import com.isim.dims.mapper.CameraCalibrationMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.SensorCalibrationMapper;
import com.isim.dims.service.impl.CalibrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 传感器标定 Service 测试
 *
 * 覆盖契约要点：无标定 200 + has_calibration=false（不报错）、
 * (slice_id, sensor_name) upsert、yaw/pitch/roll unit rad、
 * 相机点位回退 camera_calibration.vehicle_xyz、参数非法 CALIBRATION_INVALID。
 */
@ExtendWith(MockitoExtension.class)
class CalibrationServiceImplTest {

    @Mock
    private SensorCalibrationMapper sensorCalibrationMapper;

    @Mock
    private CameraCalibrationMapper cameraCalibrationMapper;

    @Mock
    private DataSliceMapper dataSliceMapper;

    @InjectMocks
    private CalibrationServiceImpl calibrationService;

    private DataSlice slice;

    @BeforeEach
    void setUp() {
        slice = new DataSlice();
        slice.setId("slice-1");
        // lenient：SliceNotFound 测试不需要该 stubbing
        lenient().when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
    }

    // ============ GET：无标定 ============

    @Test
    void getCalibrationSet_NoData_ReturnsHasCalibrationFalse() {
        when(sensorCalibrationMapper.selectList(any())).thenReturn(List.of());
        when(cameraCalibrationMapper.selectList(any())).thenReturn(List.of());

        SensorCalibrationSet set = calibrationService.getCalibrationSet("slice-1");

        assertThat(set.isHasCalibration()).isFalse();
        assertThat(set.getFrame()).isEqualTo("ego");
        assertThat(set.getUnit()).isEqualTo("m");
        assertThat(set.getSensors()).isEmpty();
        assertThat(set.getVehicle().getLengthM()).isEqualTo(4.8);
        assertThat(set.getVehicle().getWidthM()).isEqualTo(1.9);
        assertThat(set.getVehicle().getWheelbaseM()).isEqualTo(2.8);
    }

    @Test
    void getCalibrationSet_WithRows_MapsPoseJson() {
        SensorCalibration row = new SensorCalibration();
        row.setId("calib-1");
        row.setSliceId("slice-1");
        row.setSensorName("front_wide");
        row.setSensorType("camera");
        row.setFrame("ego");
        row.setUnit("m");
        row.setPoseJson("{\"x\":0.0,\"y\":0.0,\"z\":1.2,\"yaw\":0.1,\"pitch\":0.0,\"roll\":0.0}");
        when(sensorCalibrationMapper.selectList(any())).thenReturn(List.of(row));

        SensorCalibrationSet set = calibrationService.getCalibrationSet("slice-1");

        assertThat(set.isHasCalibration()).isTrue();
        assertThat(set.getSensors()).hasSize(1);
        SensorCalibrationResponse resp = set.getSensors().get(0);
        assertThat(resp.getSensorName()).isEqualTo("front_wide");
        assertThat(resp.getZ()).isEqualTo(1.2);
        assertThat(resp.getYaw()).isEqualTo(0.1); // unit: rad
    }

    @Test
    void getCalibrationSet_NoRows_FallsBackToCameraCalibration() {
        when(sensorCalibrationMapper.selectList(any())).thenReturn(List.of());

        CameraCalibration cam = new CameraCalibration();
        cam.setId("cam-1");
        cam.setSliceId("slice-1");
        cam.setSensorName("front_wide");
        cam.setVehicleXyz("[0.2, 0.0, 1.2]");
        cam.setRS2b("[0.05, 0.0, 0.0]");
        when(cameraCalibrationMapper.selectList(any())).thenReturn(List.of(cam));

        SensorCalibrationSet set = calibrationService.getCalibrationSet("slice-1");

        assertThat(set.isHasCalibration()).isTrue();
        assertThat(set.getSensors()).hasSize(1);
        SensorCalibrationResponse resp = set.getSensors().get(0);
        assertThat(resp.getX()).isEqualTo(0.2);
        assertThat(resp.getZ()).isEqualTo(1.2);
        assertThat(resp.getYaw()).isEqualTo(0.05); // r_s2b → yaw（rad）
        assertThat(resp.getSource()).isEqualTo("camera_calibration");
    }

    @Test
    void getCalibrationSet_NoRows_VehicleXyzNull_FallsBackToTS2b() {
        when(sensorCalibrationMapper.selectList(any())).thenReturn(List.of());

        CameraCalibration cam = new CameraCalibration();
        cam.setId("cam-2");
        cam.setSliceId("slice-1");
        cam.setSensorName("front_wide");
        cam.setVehicleXyz(null); // vehicle_xyz 缺失
        cam.setTS2b("[2.069, -0.018, 1.587]");
        cam.setRS2b("[-1.186, 1.217, -1.203]");
        when(cameraCalibrationMapper.selectList(any())).thenReturn(List.of(cam));

        SensorCalibrationSet set = calibrationService.getCalibrationSet("slice-1");

        assertThat(set.isHasCalibration()).isTrue();
        assertThat(set.getSensors()).hasSize(1);
        SensorCalibrationResponse resp = set.getSensors().get(0);
        assertThat(resp.getX()).isEqualTo(2.069); // t_s2b → x/y/z
        assertThat(resp.getY()).isEqualTo(-0.018);
        assertThat(resp.getZ()).isEqualTo(1.587);
        assertThat(resp.getYaw()).isEqualTo(-1.186); // r_s2b → yaw（rad）
        assertThat(resp.getPitch()).isEqualTo(1.217);
        assertThat(resp.getRoll()).isEqualTo(-1.203);
        assertThat(resp.getSource()).isEqualTo("camera_calibration");
    }

    @Test
    void getCalibrationSet_NoRows_BothPositionNull_SkipsRow() {
        when(sensorCalibrationMapper.selectList(any())).thenReturn(List.of());

        CameraCalibration cam = new CameraCalibration();
        cam.setId("cam-3");
        cam.setSliceId("slice-1");
        cam.setSensorName("front_wide");
        cam.setVehicleXyz(null);
        cam.setTS2b(null); // 两个位置源都缺失
        cam.setRS2b("[0.05, 0.0, 0.0]");
        when(cameraCalibrationMapper.selectList(any())).thenReturn(List.of(cam));

        SensorCalibrationSet set = calibrationService.getCalibrationSet("slice-1");

        assertThat(set.isHasCalibration()).isFalse();
        assertThat(set.getSensors()).isEmpty();
    }

    @Test
    void getCalibrationSet_SliceNotFound_Throws() {
        when(dataSliceMapper.selectById("bad-slice")).thenReturn(null);

        assertThatThrownBy(() -> calibrationService.getCalibrationSet("bad-slice"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("SLICE_NOT_FOUND");
    }

    // ============ POST：upsert ============

    @Test
    void createCalibration_NewSensor_Inserts() {
        when(sensorCalibrationMapper.selectOne(any())).thenReturn(null);
        when(sensorCalibrationMapper.insert(any(SensorCalibration.class))).thenReturn(1);

        SensorCalibrationRequest req = new SensorCalibrationRequest();
        req.setSensorName("result_front");
        req.setSensorType("radar");
        req.setX(0.1);
        req.setY(0.0);
        req.setZ(0.5);
        req.setYaw(0.0);
        req.setPitch(0.0);
        req.setRoll(0.0);

        SensorCalibrationResponse resp = calibrationService.createCalibration("slice-1", req);

        assertThat(resp.getSensorName()).isEqualTo("result_front");
        assertThat(resp.getFrame()).isEqualTo("ego"); // 默认值
        assertThat(resp.getUnit()).isEqualTo("m");    // 默认值
        verify(sensorCalibrationMapper).insert(any(SensorCalibration.class));
    }

    @Test
    void createCalibration_ExistingSensor_UpdatesInPlace() {
        SensorCalibration existing = new SensorCalibration();
        existing.setId("calib-1");
        existing.setSliceId("slice-1");
        existing.setSensorName("front_wide");
        existing.setSensorType("camera");
        when(sensorCalibrationMapper.selectOne(any())).thenReturn(existing);
        when(sensorCalibrationMapper.updateById(any(SensorCalibration.class))).thenReturn(1);

        SensorCalibrationRequest req = new SensorCalibrationRequest();
        req.setSensorName("front_wide");
        req.setSensorType("camera");
        req.setX(0.3);
        req.setY(0.1);
        req.setZ(1.4);
        req.setYaw(0.2);
        req.setPitch(0.0);
        req.setRoll(0.0);

        SensorCalibrationResponse resp = calibrationService.createCalibration("slice-1", req);

        assertThat(resp.getId()).isEqualTo("calib-1"); // 同名更新不换 ID
        assertThat(resp.getX()).isEqualTo(0.3);
        verify(sensorCalibrationMapper, never()).insert(any(SensorCalibration.class));
        verify(sensorCalibrationMapper).updateById(any(SensorCalibration.class));
    }

    // ============ POST：参数校验 ============

    @Test
    void createCalibration_InvalidSensorType_Throws() {
        SensorCalibrationRequest req = new SensorCalibrationRequest();
        req.setSensorName("sensor-x");
        req.setSensorType("thermal"); // 不在枚举
        req.setX(0.0);
        req.setY(0.0);
        req.setZ(0.0);
        req.setYaw(0.0);
        req.setPitch(0.0);
        req.setRoll(0.0);

        assertThatThrownBy(() -> calibrationService.createCalibration("slice-1", req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("CALIBRATION_INVALID");
    }

    @Test
    void createCalibration_MissingPose_Throws() {
        SensorCalibrationRequest req = new SensorCalibrationRequest();
        req.setSensorName("sensor-x");
        req.setSensorType("radar");
        // x/y/z/yaw/pitch/roll 缺失

        assertThatThrownBy(() -> calibrationService.createCalibration("slice-1", req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("CALIBRATION_INVALID");
    }
}
