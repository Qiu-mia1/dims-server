package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.entity.CameraCalibration;
import com.isim.dims.entity.CfdiMeta;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.mapper.CameraCalibrationMapper;
import com.isim.dims.mapper.CfdiMetaMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.CfdiMetadataService;
import com.isim.dims.util.CfdiConstants;
import com.isim.dims.util.PythonExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CFDI 元数据解析 Service 实现
 *
 * 调用 Python 脚本解析摄像头标定和 CFDI 元数据，
 * 并将 sub_span_us 分发到 sensor_stream 记录。
 *
 * 非关键步骤：解析失败不影响主流水线，仅 WARN 日志。
 * @feature: cfdi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CfdiMetadataServiceImpl implements CfdiMetadataService {

    private final CameraCalibrationMapper cameraCalibrationMapper;
    private final CfdiMetaMapper cfdiMetaMapper;
    private final SensorStreamMapper sensorStreamMapper;
    private final PythonExecutor pythonExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * h265 文件名前缀 → sensor_name 映射
     * 引用 CfdiConstants 共享常量
     */
    private static final Map<String, String> H265_SENSOR_MAP = CfdiConstants.H265_SENSOR_MAP;

    // ========================================================================
    // 摄像头标定解析
    // ========================================================================

    @Override
    public void parseCameraCalibration(String sliceId, String publicDir) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("public_dir", publicDir);
            args.put("slice_id", sliceId);

            String result = pythonExecutor.execute("parse_cfdi_calibration.py", args, 60);

            JsonNode root = objectMapper.readTree(result);
            JsonNode calibrations = root.get("calibrations");
            if (calibrations == null || !calibrations.isArray()) {
                log.warn("parse_cfdi_calibration.py returned non-array: {}", result);
                return;
            }

            int count = 0;
            for (JsonNode calib : calibrations) {
                CameraCalibration entity = new CameraCalibration();
                entity.setId(UUID.randomUUID().toString());
                entity.setSliceId(sliceId);
                entity.setSensorName(jsonText(calib, "sensor_name"));
                entity.setSerialNumber(jsonText(calib, "serial_number"));
                entity.setSensorType(jsonText(calib, "sensor_type"));
                entity.setCameraModel(jsonText(calib, "camera_model"));
                entity.setTimestampShift(jsonDouble(calib, "timestamp_shift"));
                entity.setRS2b(jsonArrayText(calib, "r_s2b"));
                entity.setTS2b(jsonArrayText(calib, "t_s2b"));
                entity.setFx(jsonDouble(calib, "fx"));
                entity.setFy(jsonDouble(calib, "fy"));
                entity.setCx(jsonDouble(calib, "cx"));
                entity.setCy(jsonDouble(calib, "cy"));
                entity.setKc2(jsonDouble(calib, "kc2"));
                entity.setKc3(jsonDouble(calib, "kc3"));
                entity.setKc4(jsonDouble(calib, "kc4"));
                entity.setKc5(jsonDouble(calib, "kc5"));
                Boolean fisheyeBool = jsonBool(calib, "is_fisheye");
                entity.setIsFisheye(fisheyeBool != null ? (fisheyeBool ? 1 : 0) : null);
                entity.setLineExposureDelay(jsonDouble(calib, "line_exposure_delay"));
                entity.setWidth(jsonInt(calib, "width"));
                entity.setHeight(jsonInt(calib, "height"));
                entity.setSuggestedRectRegion(jsonArrayText(calib, "suggested_rect_region"));
                entity.setCalibVersion(jsonText(calib, "calib_version"));
                entity.setCalibDetails(jsonText(calib, "calib_details"));
                entity.setCalibDate(jsonText(calib, "calib_date"));
                cameraCalibrationMapper.insert(entity);
                count++;
            }

            log.info("Camera calibration parsed: {} records inserted for sliceId={}", count, sliceId);

        } catch (Exception e) {
            log.warn("Failed to parse camera calibration for sliceId={}, skipping: {}",
                    sliceId, e.getMessage());
        }
    }

    // ========================================================================
    // CFDI 元数据解析
    // ========================================================================

    @Override
    public void parseCfdiMetadata(String sliceId, String publicDir) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("public_dir", publicDir);
            args.put("slice_id", sliceId);

            String result = pythonExecutor.execute("parse_cfdi_metadata.py", args, 120);

            JsonNode metaJson = objectMapper.readTree(result);

            CfdiMeta entity = new CfdiMeta();
            entity.setId(UUID.randomUUID().toString());
            entity.setSliceId(sliceId);
            entity.setCarType(jsonText(metaJson, "car_type"));
            entity.setProject(jsonText(metaJson, "project"));
            entity.setEventName(jsonText(metaJson, "event_name"));
            entity.setVin(jsonText(metaJson, "vin"));
            entity.setCollectType(jsonText(metaJson, "collect_type"));
            entity.setDataSourceEnv(jsonText(metaJson, "data_source_env"));
            entity.setMetaData(jsonObjectText(metaJson, "meta_data"));
            entity.setFilterMetaData(jsonObjectText(metaJson, "filter_meta_data"));
            entity.setLogInfoData(jsonObjectText(metaJson, "log_info_data"));
            entity.setUploadDoneData(jsonObjectText(metaJson, "upload_done_data"));
            entity.setGoodFlag(jsonText(metaJson, "good_flag"));
            entity.setDumpFinish(jsonText(metaJson, "dump_finish"));
            cfdiMetaMapper.insert(entity);

            log.info("CFDI metadata parsed for sliceId={}: carType={}, project={}, eventName={}",
                    sliceId, entity.getCarType(), entity.getProject(), entity.getEventName());

        } catch (Exception e) {
            log.warn("Failed to parse CFDI metadata for sliceId={}, skipping", sliceId, e);
        }
    }

    // ========================================================================
    // sub_span_us 分发到 sensor_stream.duration_us
    // ========================================================================

    @Override
    public void distributeSubSpanUs(String sliceId) {
        try {
            // 查询 cfdi_meta
            CfdiMeta cfdiMeta = cfdiMetaMapper.selectOne(
                    new LambdaQueryWrapper<CfdiMeta>().eq(CfdiMeta::getSliceId, sliceId)
            );
            if (cfdiMeta == null) {
                log.warn("CfdiMeta not found for sliceId={}, cannot distribute sub_span_us", sliceId);
                return;
            }

            // 从 meta_data JSON 中提取 sub_span_us
            String metaDataStr = cfdiMeta.getMetaData();
            if (metaDataStr == null || metaDataStr.isEmpty()) {
                log.warn("meta_data is empty for sliceId={}", sliceId);
                return;
            }

            JsonNode metaData = objectMapper.readTree(metaDataStr);
            JsonNode subSpanUs = metaData.get("sub_span_us");
            if (subSpanUs == null || subSpanUs.isNull()) {
                log.debug("No sub_span_us in meta_data for sliceId={}", sliceId);
                return;
            }

            // sub_span_us 是一个对象，key 为 sensor_name，value 为 duration_us
            // 通过 sensor_name 匹配 sensor_stream（通过 topic 或 metadata 匹配）
            int updatedCount = 0;
            var fields = subSpanUs.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String sensorName = entry.getKey();
                long durationUs = entry.getValue().asLong();

                // 通过 sensor_name 精确匹配 sensor_stream（使用冗余列，避免 JSON LIKE 全表扫描）
                LambdaQueryWrapper<SensorStream> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(SensorStream::getSliceId, sliceId);
                wrapper.eq(SensorStream::getSensorName, sensorName);

                var streams = sensorStreamMapper.selectList(wrapper);

                for (SensorStream stream : streams) {
                    stream.setDurationUs(durationUs);
                    sensorStreamMapper.updateById(stream);
                    updatedCount++;
                }
            }

            log.info("sub_span_us distributed: {} sensor_stream records updated for sliceId={}",
                    updatedCount, sliceId);

        } catch (Exception e) {
            log.warn("Failed to distribute sub_span_us for sliceId={}, skipping: {}",
                    sliceId, e.getMessage());
        }
    }

    // ========================================================================
    // JSON 辅助方法
    // ========================================================================

    private String jsonText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /**
     * 读取 JSON 字段，支持数组/对象节点（如 r_s2b, t_s2b）。
     * 使用 toString() 而非 asText()，因为 asText() 对 ArrayNode 返回空字符串。
     */
    private String jsonArrayText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        JsonNode val = node.get(field);
        // 如果是 TextNode，asText 即可；否则 toString() 输出合法 JSON（如 "[-1.2, 1.3, 0.5]"）
        return val.isTextual() ? val.asText() : val.toString();
    }

    /**
     * 读取 JSON 字段，支持对象/数组节点（如 meta_data, filter_meta_data）。
     * ObjectNode.asText() 返回空字符串，必须用 toString() 得到完整 JSON。
     */
    private String jsonObjectText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        JsonNode val = node.get(field);
        if (val.isTextual()) return val.asText();
        if (val.isObject() || val.isArray()) return val.toString();
        return val.asText();
    }

    private Double jsonDouble(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asDouble() : null;
    }

    private Integer jsonInt(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asInt() : null;
    }

    private Boolean jsonBool(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asBoolean() : null;
    }
}
