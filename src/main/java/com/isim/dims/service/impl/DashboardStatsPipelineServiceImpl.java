package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.entity.DashboardStatsTask;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.SliceStats;
import com.isim.dims.mapper.DashboardStatsTaskMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.SliceStatsMapper;
import com.isim.dims.service.DashboardStatsPipelineService;
import com.isim.dims.util.PythonExecutor;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 驾驶舱统计提取流水线 Service 实现.
 *
 * <p>在 bag 导入 READY 后异步提取驾驶舱所需的统计指标，写入
 * {@code slice_stats} 表。独立 Bean 确保 {@code processExtraction} 的
 * {@code @Async} 通过 Spring AOP 代理正常生效。</p>
 *
 * <p>流水线步骤：</p>
 * <pre>
 * 1. 创建 task(PENDING) + stats(PENDING) → @Async processExtraction
 * 2. pythonExecutor.execute("extract_dashboard_stats.py", args, 300)
 * 3. 解析 stdout JSON → 填充 SliceStats 字段
 * 4. SUCCESS：回写 stats + task 终态
 * 5. FAILED：回写 error_msg
 * </pre>
 *
 * <p>产物：key 规范 {@code cockpit/{sliceId}/cockpit_stats.json}。
 * local 模式 Python 直接写最终位置；minio 模式先写 staging，成功后
 * commitStagedDir 上传 MinIO（与场景提取流水线同一模式，2026-08-14 A6 修复）。</p>
 *
 * <p>状态机：PENDING → RUNNING → SUCCESS | FAILED</p>
 * @feature: dashboard
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardStatsPipelineServiceImpl implements DashboardStatsPipelineService {

    private static final int PYTHON_TIMEOUT_SEC = 300;

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final DashboardStatsTaskMapper taskMapper;
    private final SliceStatsMapper statsMapper;
    private final DataSliceMapper dataSliceMapper;
    private final PythonExecutor pythonExecutor;
    private final StorageAccess storageAccess;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 自引用代理（@Lazy 延迟注入，避免循环依赖）.
     * triggerExtraction 通过它调用 @Async 的 processExtraction，
     * 确保 @Async 拦截器经 AOP 代理生效（同类自调用会绕过代理）.
     */
    @Autowired
    @Lazy
    private DashboardStatsPipelineService self;

    // ========================================================================
    // 触发任务
    // ========================================================================

    @Override
    public void triggerExtraction(String sliceId) {
        // 1. 切片存在性校验
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            log.warn("Dashboard stats extraction skipped: slice not found, sliceId={}", sliceId);
            return;
        }

        // 2. 幂等：已有 SUCCESS 则跳过
        SliceStats existing = getStatsBySliceId(sliceId);
        if (existing != null && STATUS_SUCCESS.equals(existing.getStatus())) {
            log.debug("Dashboard stats already extracted: sliceId={}", sliceId);
            return;
        }

        // 3. 创建 PENDING 任务
        DashboardStatsTask task = new DashboardStatsTask();
        task.setId(UUID.randomUUID().toString());
        task.setSliceId(sliceId);
        task.setStatus(STATUS_PENDING);
        task.setProgress(0);
        taskMapper.insert(task);

        // 4. 创建/重置 PENDING 统计行（slice_id UNIQUE，存在则 update，不存在则 insert）
        if (existing == null) {
            existing = new SliceStats();
            existing.setId(UUID.randomUUID().toString());
            existing.setSliceId(sliceId);
            existing.setTaskId(task.getId());
            existing.setStatus(STATUS_PENDING);
            statsMapper.insert(existing);
        } else {
            existing.setTaskId(task.getId());
            existing.setStatus(STATUS_PENDING);
            existing.setErrorMsg(null);
            statsMapper.updateById(existing);
        }

        // 5. 委派异步流水线（@Lazy 自引用代理，@Async 经 AOP 生效）
        self.processExtraction(task);

        log.info("Dashboard stats extraction task created: taskId={}, sliceId={}",
                task.getId(), sliceId);
    }

    // ========================================================================
    // 异步流水线
    // ========================================================================

    @Override
    @Async("cockpitExtractExecutor")
    public void processExtraction(DashboardStatsTask task) {
        String taskId = task.getId();
        String sliceId = task.getSliceId();
        log.info("Dashboard stats extraction started: taskId={}, sliceId={}", taskId, sliceId);

        // 幂等抢占：仅 PENDING 可进入 RUNNING
        DashboardStatsTask latest = taskMapper.selectById(taskId);
        if (latest == null || !STATUS_PENDING.equals(latest.getStatus())) {
            log.warn("Dashboard stats extraction skipped (task not PENDING): taskId={}", taskId);
            return;
        }

        try {
            latest.setStatus(STATUS_RUNNING);
            latest.setProgress(50);
            taskMapper.updateById(latest);

            // 校验 bag 存储引用并物化为本地路径（Python 进程消费）
            String bagPath = resolveBagPath(sliceId);

            // 产物 key 规范 cockpit/{id}/cockpit_stats.json：
            // local 模式 Python 直接写最终位置；minio 模式先写 staging，成功后上传 MinIO
            Path outputPath;
            if (storageAccess.isMinioMode()) {
                Path stage = storageAccess.stageDir("cockpit").resolve(sliceId);
                storageAccess.deleteRecursively(stage); // 清理上次失败遗留 staging
                outputPath = stage.resolve("cockpit_stats.json");
            } else {
                outputPath = storageAccess.resolveLocalPath(StorageKeys.cockpitStats(sliceId));
            }
            Files.createDirectories(outputPath.getParent());

            // 调用 Python 提取脚本
            Map<String, Object> args = new HashMap<>();
            args.put("bag_path", bagPath);
            args.put("output_path", outputPath.toString());

            String resultJson = pythonExecutor.execute(
                    "extract_dashboard_stats.py", args, PYTHON_TIMEOUT_SEC);
            JsonNode result = objectMapper.readTree(resultJson);

            if (!result.path("success").asBoolean(false)) {
                String error = result.path("error").asText("Unknown error");
                throw new RuntimeException("extract_dashboard_stats.py failed: " + error);
            }

            // 获取或创建 SliceStats（按 slice_id 查，slice_id UNIQUE）
            SliceStats stats = getStatsBySliceId(sliceId);
            boolean isNew = (stats == null);
            if (isNew) {
                stats = new SliceStats();
                stats.setId(UUID.randomUUID().toString());
                stats.setSliceId(sliceId);
            }
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice != null && slice.getVehicleType() != null && !slice.getVehicleType().isBlank()) {
                stats.setVehicleType(slice.getVehicleType());
            }

            stats.setTaskId(taskId);
            fillStatsFromJson(stats, result);

            // Hirain 835 统一车型兜底
            if (isBlank(stats.getVehicleType()) && slice != null && "Hirain".equals(slice.getSourceType())) {
                stats.setVehicleType("CX835base");
            }
            stats.setStatus(STATUS_SUCCESS);
            stats.setErrorMsg(null);

            if (isNew) {
                statsMapper.insert(stats);
            } else {
                statsMapper.updateById(stats);
            }

            // minio 模式：staging 产物上传 MinIO 并清理 staging（local 模式 no-op，Python 已写最终位置）
            storageAccess.commitStagedDir(outputPath.getParent(), StorageKeys.cockpitPrefix(sliceId));

            // 任务 SUCCESS
            latest.setStatus(STATUS_SUCCESS);
            latest.setProgress(100);
            latest.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(latest);

            log.info("Dashboard stats extraction completed: taskId={}, sliceId={}, "
                            + "vehicleType={}, roadScene={}, speedAvg={}",
                    taskId, sliceId,
                    stats.getVehicleType(), stats.getRoadScene(), stats.getSpeedAvg());

        } catch (Exception e) {
            log.error("Dashboard stats extraction FAILED: taskId={}, sliceId={}",
                    taskId, sliceId, e);

            // 回写 FAILED 终态
            try {
                latest.setStatus(STATUS_FAILED);
                latest.setErrorMsg(truncate(e.getMessage(), 2000));
                latest.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
                taskMapper.updateById(latest);

                SliceStats stats = getStatsBySliceId(sliceId);
                if (stats != null) {
                    stats.setStatus(STATUS_FAILED);
                    stats.setErrorMsg(truncate(e.getMessage(), 2000));
                    statsMapper.updateById(stats);
                }
            } catch (Exception ex) {
                log.error("Failed to persist FAILED state: taskId={}", taskId, ex);
            }
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 按 slice_id 查询 SliceStats（slice_id 有 UNIQUE 约束，最多 1 行）.
     */
    private SliceStats getStatsBySliceId(String sliceId) {
        return statsMapper.selectOne(
                new LambdaQueryWrapper<SliceStats>()
                        .eq(SliceStats::getSliceId, sliceId));
    }

    /**
     * 从 Python 输出的 JSON 填充 SliceStats 实体.
     */
    private void fillStatsFromJson(SliceStats stats, JsonNode json) {
        // 车辆信息：仅当 JSON 显式返回非空车型时才覆盖，保留 slice / 默认值
        JsonNode vehicleTypeNode = json.get("vehicle_type");
        if (vehicleTypeNode != null && !vehicleTypeNode.isNull()
                && !vehicleTypeNode.asText().isBlank()) {
            stats.setVehicleType(vehicleTypeNode.asText());
        }

        // 里程（km）
        stats.setVehicleMileageStart(nullableDouble(json, "vehicle_mileage_start"));
        stats.setVehicleMileageEnd(nullableDouble(json, "vehicle_mileage_end"));
        stats.setVehicleMileageDelta(nullableDouble(json, "vehicle_mileage_delta"));

        // 车速（m/s）
        stats.setSpeedAvg(nullableDouble(json, "speed_avg"));
        stats.setSpeedMax(nullableDouble(json, "speed_max"));
        stats.setSpeedMin(nullableDouble(json, "speed_min"));

        // 道路场景
        stats.setRoadScene(nullableText(json, "road_scene"));
        stats.setRoadSceneSource(nullableText(json, "road_scene_source"));

        // 功能状态快照（《智能功能状态灯 · 信号源说明》）
        stats.setNoaActive(nullableBoolean(json, "noa_active"));
        stats.setAebActive(nullableBoolean(json, "aeb_active"));
        stats.setApaActive(nullableBoolean(json, "apa_active"));
        stats.setAccActive(nullableBoolean(json, "acc_active"));
        stats.setLccActive(nullableBoolean(json, "lcc_active"));
        stats.setRoamingActive(nullableBoolean(json, "roaming_active"));
        stats.setAvpActive(nullableBoolean(json, "avp_active"));
        stats.setFcwActive(nullableBoolean(json, "fcw_active"));
        stats.setAesActive(nullableBoolean(json, "aes_active"));
        stats.setEssActive(nullableBoolean(json, "ess_active"));

        // 功能状态统计
        stats.setNoaActivationCount(nullableInt(json, "noa_activation_count"));
        stats.setNoaTotalDurationS(nullableDouble(json, "noa_total_duration_s"));
        stats.setNoaSuccessCount(nullableInt(json, "noa_success_count"));
        stats.setNoaFailCount(nullableInt(json, "noa_fail_count"));
        stats.setNoaExitCount(nullableInt(json, "noa_exit_count"));
        stats.setNoaMileageKm(nullableDouble(json, "noa_mileage_km"));
        stats.setNoaSuccessRate(nullableDouble(json, "noa_success_rate"));
        stats.setAebTriggerCount(nullableInt(json, "aeb_trigger_count"));
        stats.setAebTotalScenes(nullableInt(json, "aeb_total_scenes"));
        stats.setAebTotalDurationS(nullableDouble(json, "aeb_total_duration_s"));
        stats.setAebMileageKm(nullableDouble(json, "aeb_mileage_km"));
        stats.setApaTaskCount(nullableInt(json, "apa_task_count"));
        stats.setApaSuccessCount(nullableInt(json, "apa_success_count"));
        stats.setApaSuccessRate(nullableDouble(json, "apa_success_rate"));
        stats.setApaTotalDurationS(nullableDouble(json, "apa_total_duration_s"));
        stats.setApaMileageKm(nullableDouble(json, "apa_mileage_km"));
        stats.setAccActivationCount(nullableInt(json, "acc_activation_count"));
        stats.setAccTotalDurationS(nullableDouble(json, "acc_total_duration_s"));
        stats.setAccMileageKm(nullableDouble(json, "acc_mileage_km"));
        stats.setLccActivationCount(nullableInt(json, "lcc_activation_count"));
        stats.setLccTotalDurationS(nullableDouble(json, "lcc_total_duration_s"));
        stats.setLccMileageKm(nullableDouble(json, "lcc_mileage_km"));

        // 接管/介入
        stats.setTakeoverCount(nullableInt(json, "takeover_count"));
        stats.setTakeoverRate(nullableDouble(json, "takeover_rate"));
        stats.setTakeoverReasons(nullableJsonText(json, "takeover_reasons"));
        stats.setInterventionCount(nullableInt(json, "intervention_count"));
        stats.setInterventionRate(nullableDouble(json, "intervention_rate"));
        stats.setSceneBreakdown(nullableJsonText(json, "scene_breakdown"));

        // 工况
        stats.setCurvatureAvg(nullableDouble(json, "curvature_avg"));
        stats.setCurvatureMax(nullableDouble(json, "curvature_max"));
        stats.setGnssSatNumAvg(nullableDouble(json, "gnss_sat_num_avg"));
        stats.setGnssHdopAvg(nullableDouble(json, "gnss_hdop_avg"));
        stats.setLaneChangeCountLeft(nullableInt(json, "lane_change_count_left"));
        stats.setLaneChangeCountRight(nullableInt(json, "lane_change_count_right"));
        stats.setHighwayMode(nullableBoolean(json, "highway_mode"));
    }

    private String resolveBagPath(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null || slice.getFilePath() == null || slice.getFilePath().isBlank()) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }
        String ref = slice.getFilePath();
        // bag 存在性检查收口到存储层（兼容绝对路径 / 相对 key）
        if (!storageAccess.exists(ref)) {
            throw new RuntimeException("BAG_NOT_FOUND: " + ref);
        }
        return storageAccess.materializeLocal(ref).toString();
    }

    // ========================================================================
    // JSON 安全取值辅助
    // ========================================================================

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val == null || val.isNull()) ? null : val.asText();
    }

    /** Like nullableText but serialises objects/arrays with toString() for JSON columns. */
    private static String nullableJsonText(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || val.isNull()) return null;
        return val.isValueNode() ? val.asText() : val.toString();
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val == null || val.isNull()) ? null : val.asDouble();
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val == null || val.isNull()) ? null : val.asInt();
    }

    private static Boolean nullableBoolean(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val == null || val.isNull()) ? null : val.asBoolean(false);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
