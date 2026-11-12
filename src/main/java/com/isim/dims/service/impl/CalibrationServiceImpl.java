package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.SensorCalibrationRequest;
import com.isim.dims.dto.SensorCalibrationResponse;
import com.isim.dims.dto.SensorCalibrationSet;
import com.isim.dims.dto.VehicleSpec;
import com.isim.dims.entity.CameraCalibration;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.SensorCalibration;
import com.isim.dims.mapper.CameraCalibrationMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.SensorCalibrationMapper;
import com.isim.dims.service.CalibrationService;
import com.isim.dims.util.ReplayTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 传感器标定 Service 实现
 *
 * <p>相机点位读取优先级（DESIGN §7.3）：sensor_calibration → camera_calibration.vehicle_xyz
 * → 默认布局 + UI 标注「标定缺失」。</p>
 * @feature: calibration
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalibrationServiceImpl implements CalibrationService {

    /** 可摄入标定的传感器类型（契约 SensorCalibrationType 枚举） */
    private static final Set<String> VALID_SENSOR_TYPES =
            Set.of("camera", "radar", "ultrasonic", "imu", "lidar");

    /** 无标定时的默认车体尺寸（契约：如 4.8/1.9/2.8） */
    private static final double DEFAULT_LENGTH_M = 4.8;
    private static final double DEFAULT_WIDTH_M = 1.9;
    private static final double DEFAULT_WHEELBASE_M = 2.8;

    private final SensorCalibrationMapper sensorCalibrationMapper;
    private final CameraCalibrationMapper cameraCalibrationMapper;
    private final DataSliceMapper dataSliceMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========================================================================
    // 查询
    // ========================================================================

    @Override
    public SensorCalibrationSet getCalibrationSet(String sliceId) {
        requireSlice(sliceId);

        List<SensorCalibration> rows = sensorCalibrationMapper.selectList(
                new LambdaQueryWrapper<SensorCalibration>()
                        .eq(SensorCalibration::getSliceId, sliceId)
                        .orderByAsc(SensorCalibration::getSensorName)
        );

        SensorCalibrationSet set = new SensorCalibrationSet();
        set.setFrame("ego");
        set.setUnit("m");
        set.setVehicle(defaultVehicle());

        if (rows.isEmpty()) {
            // 回退 camera_calibration.vehicle_xyz（CFDI 相机外参）
            set.setSensors(fallbackCameraCalibrations(sliceId));
        } else {
            set.setSensors(rows.stream().map(this::toResponse).toList());
        }
        set.setHasCalibration(!set.getSensors().isEmpty());
        return set;
    }

    /**
     * 回退 camera_calibration：位置优先 vehicle_xyz、缺失时回退 t_s2b → x/y/z；
     * r_s2b → yaw/pitch/roll（rad）
     */
    private List<SensorCalibrationResponse> fallbackCameraCalibrations(String sliceId) {
        List<CameraCalibration> rows = cameraCalibrationMapper.selectList(
                new LambdaQueryWrapper<CameraCalibration>()
                        .eq(CameraCalibration::getSliceId, sliceId)
        );
        List<SensorCalibrationResponse> sensors = new ArrayList<>();
        for (CameraCalibration row : rows) {
            String positionSource = row.getVehicleXyz();
            if (positionSource == null || positionSource.isBlank()) {
                // vehicle_xyz 缺失 → 回退 t_s2b（CFDI 平移向量 [tx,ty,tz] m）
                positionSource = row.getTS2b();
            }
            if (positionSource == null || positionSource.isBlank()) {
                continue;
            }
            try {
                JsonNode xyz = objectMapper.readTree(positionSource);
                if (!xyz.isArray() || xyz.size() < 3) {
                    continue;
                }
                SensorCalibrationResponse resp = new SensorCalibrationResponse();
                resp.setId(row.getId());
                resp.setSliceId(sliceId);
                resp.setSensorName(row.getSensorName());
                resp.setSensorType(row.getSensorType() != null ? row.getSensorType() : "camera");
                resp.setX(xyz.get(0).asDouble());
                resp.setY(xyz.get(1).asDouble());
                resp.setZ(xyz.get(2).asDouble());
                // r_s2b 旋转向量 → yaw/pitch/roll（rad）；无则 0
                double[] rpy = parseRotation(row.getRS2b());
                resp.setYaw(rpy[0]);
                resp.setPitch(rpy[1]);
                resp.setRoll(rpy[2]);
                resp.setFrame("ego");
                resp.setUnit("m");
                resp.setSource("camera_calibration");
                resp.setCreatedAt(ReplayTimeUtil.formatLocalDateTime(row.getCreatedAt()));
                sensors.add(resp);
            } catch (Exception e) {
                log.warn("Failed to parse camera_calibration position (vehicle_xyz/t_s2b): sliceId={}, sensor={}",
                        sliceId, row.getSensorName(), e);
            }
        }
        return sensors;
    }

    private double[] parseRotation(String rS2b) {
        if (rS2b == null || rS2b.isBlank()) {
            return new double[]{0, 0, 0};
        }
        try {
            JsonNode node = objectMapper.readTree(rS2b);
            if (node.isArray() && node.size() >= 3) {
                return new double[]{node.get(0).asDouble(), node.get(1).asDouble(), node.get(2).asDouble()};
            }
        } catch (Exception e) {
            log.warn("Failed to parse r_s2b: {}", rS2b, e);
        }
        return new double[]{0, 0, 0};
    }

    // ========================================================================
    // 摄入（upsert）
    // ========================================================================

    @Override
    public SensorCalibrationResponse createCalibration(String sliceId, SensorCalibrationRequest request) {
        requireSlice(sliceId);
        validate(request);

        SensorCalibration existing = sensorCalibrationMapper.selectOne(
                new LambdaQueryWrapper<SensorCalibration>()
                        .eq(SensorCalibration::getSliceId, sliceId)
                        .eq(SensorCalibration::getSensorName, request.getSensorName())
                        .last("LIMIT 1")
        );

        SensorCalibration entity;
        if (existing != null) {
            entity = existing;
            entity.setSensorType(request.getSensorType());
            entity.setFrame(request.getFrame() != null ? request.getFrame() : "ego");
            entity.setUnit(request.getUnit() != null ? request.getUnit() : "m");
            entity.setPoseJson(serializePose(request));
            entity.setSource(request.getSource() != null ? request.getSource() : "manual");
            sensorCalibrationMapper.updateById(entity);
            log.info("Sensor calibration updated: sliceId={}, sensorName={}", sliceId, request.getSensorName());
        } else {
            entity = new SensorCalibration();
            entity.setId(UUID.randomUUID().toString());
            entity.setSliceId(sliceId);
            entity.setSensorName(request.getSensorName());
            entity.setSensorType(request.getSensorType());
            entity.setFrame(request.getFrame() != null ? request.getFrame() : "ego");
            entity.setUnit(request.getUnit() != null ? request.getUnit() : "m");
            entity.setPoseJson(serializePose(request));
            entity.setSource(request.getSource() != null ? request.getSource() : "manual");
            sensorCalibrationMapper.insert(entity);
            log.info("Sensor calibration created: sliceId={}, sensorName={}", sliceId, request.getSensorName());
        }
        return toResponse(entity);
    }

    private void validate(SensorCalibrationRequest request) {
        if (request.getSensorName() == null || request.getSensorName().isBlank()) {
            throw new RuntimeException("CALIBRATION_INVALID: sensor_name is required");
        }
        if (request.getSensorType() == null || !VALID_SENSOR_TYPES.contains(request.getSensorType())) {
            throw new RuntimeException("CALIBRATION_INVALID: sensor_type must be one of "
                    + VALID_SENSOR_TYPES + ", got=" + request.getSensorType());
        }
        if (!isFinite(request.getX()) || !isFinite(request.getY()) || !isFinite(request.getZ())
                || !isFinite(request.getYaw()) || !isFinite(request.getPitch()) || !isFinite(request.getRoll())) {
            throw new RuntimeException("CALIBRATION_INVALID: x/y/z/yaw/pitch/roll are required "
                    + "(x/y/z unit: m, yaw/pitch/roll unit: rad)");
        }
    }

    private boolean isFinite(Double v) {
        return v != null && Double.isFinite(v);
    }

    private String serializePose(SensorCalibrationRequest request) {
        Map<String, Object> pose = new LinkedHashMap<>();
        pose.put("x", request.getX());
        pose.put("y", request.getY());
        pose.put("z", request.getZ());
        pose.put("yaw", request.getYaw());
        pose.put("pitch", request.getPitch());
        pose.put("roll", request.getRoll());
        try {
            return objectMapper.writeValueAsString(pose);
        } catch (Exception e) {
            throw new RuntimeException("CALIBRATION_INVALID: failed to serialize pose", e);
        }
    }

    // ========================================================================
    // 转换
    // ========================================================================

    private SensorCalibrationResponse toResponse(SensorCalibration entity) {
        SensorCalibrationResponse resp = new SensorCalibrationResponse();
        resp.setId(entity.getId());
        resp.setSliceId(entity.getSliceId());
        resp.setSensorName(entity.getSensorName());
        resp.setSensorType(entity.getSensorType());
        resp.setFrame(entity.getFrame() != null ? entity.getFrame() : "ego");
        resp.setUnit(entity.getUnit() != null ? entity.getUnit() : "m");
        resp.setSource(entity.getSource());
        resp.setCreatedAt(ReplayTimeUtil.formatLocalDateTime(entity.getCreatedAt()));

        JsonNode pose = parsePose(entity.getPoseJson());
        resp.setX(pose.hasNonNull("x") ? pose.get("x").asDouble() : null);
        resp.setY(pose.hasNonNull("y") ? pose.get("y").asDouble() : null);
        resp.setZ(pose.hasNonNull("z") ? pose.get("z").asDouble() : null);
        resp.setYaw(pose.hasNonNull("yaw") ? pose.get("yaw").asDouble() : null);
        resp.setPitch(pose.hasNonNull("pitch") ? pose.get("pitch").asDouble() : null);
        resp.setRoll(pose.hasNonNull("roll") ? pose.get("roll").asDouble() : null);
        return resp;
    }

    private JsonNode parsePose(String poseJson) {
        if (poseJson == null || poseJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(poseJson);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            log.warn("Failed to parse pose_json: {}", poseJson, e);
            return objectMapper.createObjectNode();
        }
    }

    private VehicleSpec defaultVehicle() {
        VehicleSpec spec = new VehicleSpec();
        spec.setLengthM(DEFAULT_LENGTH_M);
        spec.setWidthM(DEFAULT_WIDTH_M);
        spec.setWheelbaseM(DEFAULT_WHEELBASE_M);
        return spec;
    }

    private DataSlice requireSlice(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }
        return slice;
    }
}
