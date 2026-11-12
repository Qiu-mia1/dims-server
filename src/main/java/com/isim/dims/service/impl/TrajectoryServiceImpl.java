package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.TrajectoryQueryResult;
import com.isim.dims.dto.TrajectoryResponse;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.GpsTrajectory;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.GpsTrajectoryMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.TrajectoryService;
import com.isim.dims.service.VisualizationPipelineService;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GPS 轨迹 Service 实现
 *
 * 核心业务逻辑：
 * 1. getTrajectory：三级判定
 *    a. 先查 gps_trajectory 缓存表 → 有则返回 READY
 *    b. 缓存为空 → 查 sensor_stream 判断是否有 GPS/IMU 能力
 *    c. 有 GPS 能力 → 校验 bag 文件 → 自动触发异步提取（防重复触发），返回 NOT_EXTRACTED
 *    无 GPS 能力 → 返回 NO_GPS_DATA（确认为空），导入未完成时返回 IMPORT_NOT_READY
 * 2. extractTrajectory：创建任务，委派异步提取流水线（独立 Bean，@Async 通过 Spring AOP 生效）
 * 3. getTrajectoryTask：从内存任务存储查询进度
 * @feature: trajectory
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrajectoryServiceImpl implements TrajectoryService {

    private final GpsTrajectoryMapper gpsTrajectoryMapper;
    private final DataSliceMapper dataSliceMapper;
    private final SensorStreamMapper sensorStreamMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final StorageAccess storageAccess;
    private final VisualizationPipelineService visualizationPipelineService;

    // ========================================================================
    // 查询 GPS 轨迹
    // ========================================================================

    @Override
    public TrajectoryQueryResult getTrajectory(String sliceId) {
        // 1. 验证切片存在
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }

        // 2. 查询轨迹点（按时间升序）—— 一级：gps_trajectory 缓存
        List<GpsTrajectory> points = gpsTrajectoryMapper.selectList(
                new LambdaQueryWrapper<GpsTrajectory>()
                        .eq(GpsTrajectory::getSliceId, sliceId)
                        .orderByAsc(GpsTrajectory::getTs)
        );

        if (!points.isEmpty()) {
            return result("READY", null, toTrajectoryResponse(sliceId, points), null);
        }

        // 2.5 已确认空标记：提取执行过但结果为 0 点（如 odometry/heading 被归为 gnss_imu
        // 但无实际 GNSS 数据）→ 直接返回空结论，避免每次 GET 重复触发异步提取造成死循环
        if ("EMPTY".equals(slice.getTrajectoryStatus())) {
            return result("NO_GPS_DATA", null, null,
                    "该切片轨迹已提取并确认为空（trajectory_status=EMPTY，checked_at="
                            + (slice.getTrajectoryCheckedAt() != null
                                ? slice.getTrajectoryCheckedAt().atZone(ZoneOffset.UTC)
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                : "N/A") + "），不再重复触发提取");
        }

        // 3. 缓存为空 → 二级：查 sensor_stream 判断该切片是否具备 GPS 能力
        boolean hasGpsStream = sensorStreamMapper.countGpsStreams(sliceId) > 0;
        if (!hasGpsStream) {
            // 辅助判断：查 ingestion_task 导入流水线状态。
            // 导入未完成时 sensor_stream 可能尚未写入，不能下"无 GPS"结论。
            IngestionTask importTask = findImportTask(sliceId);
            String importStatus = importTask != null ? importTask.getStatus() : "N/A";
            if (importTask != null && !"READY".equals(importTask.getStatus())
                    && !"ERROR".equals(importTask.getStatus())) {
                return result("IMPORT_NOT_READY", null, null,
                        "切片导入流水线未完成（ingestion_task.status=" + importStatus
                                + "），暂无法判断 GPS 数据情况，请稍后重试");
            }
            return result("NO_GPS_DATA", null, null,
                    "切片无 GPS/IMU 传感器流（sensor_stream 无 gnss_imu 记录，ingestion_task.status="
                            + importStatus + "），该切片确认无 GPS 数据");
        }

        // 4. 有 GPS 能力但无缓存轨迹 → 校验 bag 文件（含 file_path 自愈，仅 local 模式）
        String bagPath = resolveBagFilePath(slice);
        if (bagPath == null || !storageAccess.exists(bagPath)) {
            return result("BAG_FILE_MISSING", null, null,
                    "检测到 GPS 传感器流但 bag 文件不存在: " + slice.getFilePath()
                            + "（存储引用不存在，且 " + StorageKeys.originalBag(sliceId) + " 也不存在）");
        }

        // 5. 防重复触发：已有进行中的提取任务则复用，不重复创建
        String activeTaskId = visualizationPipelineService.findActiveTaskBySlice(sliceId, "trajectory");
        if (activeTaskId != null) {
            return result("NOT_EXTRACTED", activeTaskId, null,
                    "检测到 GPS 传感器流但无缓存轨迹，提取任务进行中（taskId=" + activeTaskId + "）");
        }

        // 6. 自动触发异步提取（GET 侧效应：缓存缺失时后台自动补齐）
        String taskId = visualizationPipelineService.createTask(sliceId, "trajectory");
        visualizationPipelineService.processExtractTrajectory(taskId, sliceId, bagPath, new ExtractRequest());
        log.info("Trajectory cache miss with GPS stream present, auto-triggered extraction: "
                + "sliceId={}, taskId={}", sliceId, taskId);
        return result("NOT_EXTRACTED", taskId, null,
                "检测到 GPS 传感器流但无缓存轨迹，已自动触发异步提取（taskId=" + taskId + "）");
    }

    // ========================================================================
    // 异步提取 GPS 轨迹
    // ========================================================================

    @Override
    public Map<String, String> extractTrajectory(String sliceId, ExtractRequest request) {
        // 1. 验证切片存在
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }

        // 2. 解析有效 bag 存储引用（file_path 自愈仅 local 模式；minio 模式归档记录在此抛 410）
        String bagPath = resolveBagFilePath(slice);
        if (bagPath == null || !storageAccess.exists(bagPath)) {
            // 快速失败：避免创建注定失败的提取任务
            throw new RuntimeException("TRAJECTORY_BAG_FILE_MISSING: slice " + sliceId
                    + " has GPS sensor stream but bag file not found: " + slice.getFilePath());
        }

        // 3. 创建任务（通过 pipeline 服务管理任务状态）
        String taskId = visualizationPipelineService.createTask(sliceId, "trajectory");

        // 4. 委派异步流水线（独立 Bean，@Async 通过 Spring AOP 正常生效）
        visualizationPipelineService.processExtractTrajectory(taskId, sliceId, bagPath, request);

        // 5. 返回任务信息
        log.info("Trajectory extraction task created: taskId={}, sliceId={}", taskId, sliceId);

        Map<String, String> result = new HashMap<>();
        result.put("task_id", taskId);
        result.put("status", "PROCESSING");
        return result;
    }

    // ========================================================================
    // 查询轨迹提取任务进度
    // ========================================================================

    @Override
    public Map<String, Object> getTrajectoryTask(String taskId) {
        Map<String, Object> snapshot = visualizationPipelineService.getTaskSnapshot(taskId);
        if (snapshot == null) {
            // 内存中未找到 → 尝试从磁盘恢复（任务已清理或服务重启）
            snapshot = visualizationPipelineService.recoverTaskFromDisk(taskId);
        }
        if (snapshot == null) {
            throw new RuntimeException("TRAJECTORY_TASK_NOT_FOUND: " + taskId);
        }
        return snapshot;
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 构造 TrajectoryQueryResult 快捷方法
     */
    private TrajectoryQueryResult result(String status, String taskId,
                                         TrajectoryResponse trajectory, String message) {
        TrajectoryQueryResult r = new TrajectoryQueryResult();
        r.setStatus(status);
        r.setTaskId(taskId);
        r.setTrajectory(trajectory);
        r.setMessage(message);
        return r;
    }

    /**
     * 解析切片 bag 文件的有效存储引用（file_path 自愈，仅 local 模式）
     *
     * <p>local 模式：若 data_slice.file_path 指向的文件不存在（典型场景：CFDI 切片
     * file_path 仍指向已删除的 original.tar.gz），则尝试标准产物 key
     * {@code slices/{id}/original.bag}；若存在则自动修复 file_path 并返回。
     * minio 模式：直接返回规范 key（归档记录在此抛 StorageArchivedException → 410），
     * 禁用 self-heal——存量 19GB 本地数据只归档不迁移，MinIO 中不存在可回写的对象。</p>
     *
     * @param slice 切片实体
     * @return 有效的 bag 存储引用；找不到时返回原 file_path（可能为 null 或不存在）
     */
    private String resolveBagFilePath(DataSlice slice) {
        String resolved = storageAccess.resolveBagKey(slice);
        if (storageAccess.isMinioMode()) {
            return resolved;
        }

        if (!storageAccess.exists(resolved)) {
            String candidateKey = StorageKeys.originalBag(slice.getId());
            if (storageAccess.exists(candidateKey)) {
                log.warn("file_path self-heal: sliceId={}, stored={}, repaired={}",
                        slice.getId(), resolved, candidateKey);
                slice.setFilePath(candidateKey);
                dataSliceMapper.updateById(slice);
                return candidateKey;
            }
        }
        return resolved;
    }

    /**
     * 查询切片的导入任务（辅助判断 sensor_stream 数据是否可信）
     */
    private IngestionTask findImportTask(String sliceId) {
        return ingestionTaskMapper.selectOne(
                new LambdaQueryWrapper<IngestionTask>()
                        .eq(IngestionTask::getSliceId, sliceId)
                        .last("LIMIT 1")
        );
    }

    /**
     * 构造 TrajectoryResponse（含摘要统计）
     */
    private TrajectoryResponse toTrajectoryResponse(String sliceId, List<GpsTrajectory> points) {
        TrajectoryResponse resp = new TrajectoryResponse();
        resp.setSliceId(sliceId);

        // 轨迹点转换
        List<TrajectoryResponse.TrajectoryPoint> trajectoryPoints = points.stream()
                .map(this::toTrajectoryPoint)
                .collect(Collectors.toList());
        resp.setTrajectory(trajectoryPoints);

        // 摘要统计
        TrajectoryResponse.TrajectorySummary summary = new TrajectoryResponse.TrajectorySummary();
        summary.setPointCount(points.size());

        GpsTrajectory first = points.get(0);
        GpsTrajectory last = points.get(points.size() - 1);
        summary.setStartLat(first.getLat());
        summary.setStartLon(first.getLon());
        summary.setEndLat(last.getLat());
        summary.setEndLon(last.getLon());

        // 计算距离和速度
        double totalDistance = 0.0;
        double avgSpeed = 0.0;
        double maxSpeed = 0.0;
        int speedCount = 0;

        for (int i = 1; i < points.size(); i++) {
            GpsTrajectory prev = points.get(i - 1);
            GpsTrajectory curr = points.get(i);
            totalDistance += haversineDistance(prev.getLat(), prev.getLon(), curr.getLat(), curr.getLon());
            if (curr.getSpeed() != null) {
                avgSpeed += curr.getSpeed();
                speedCount++;
                maxSpeed = Math.max(maxSpeed, curr.getSpeed());
            }
        }

        summary.setTotalDistanceM(totalDistance);
        summary.setAvgSpeedMps(speedCount > 0 ? avgSpeed / speedCount : 0.0);
        summary.setMaxSpeedMps(maxSpeed);
        resp.setSummary(summary);

        resp.setExtractedAt(points.stream()
                .map(GpsTrajectory::getCreatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .map(this::formatTime)
                .orElse(null));

        return resp;
    }

    /**
     * GpsTrajectory → TrajectoryPoint 转换
     */
    private TrajectoryResponse.TrajectoryPoint toTrajectoryPoint(GpsTrajectory pt) {
        TrajectoryResponse.TrajectoryPoint tp = new TrajectoryResponse.TrajectoryPoint();
        tp.setTs(formatTime(pt.getTs()));
        tp.setLat(pt.getLat());
        tp.setLon(pt.getLon());
        tp.setAltitude(pt.getAltitude());
        tp.setHeading(pt.getHeading());
        tp.setSpeed(pt.getSpeed());
        return tp;
    }

    /**
     * Haversine 公式计算两点间距离（米）
     */
    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000; // 地球半径（米）
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * LocalDateTime → RFC3339 字符串
     */
    private String formatTime(LocalDateTime dt) {
        if (dt == null) return null;
        return dt.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
