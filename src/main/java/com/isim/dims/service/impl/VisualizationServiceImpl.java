package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.config.CameraEventConfig;
import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.SignalDataResponse;
import com.isim.dims.dto.VideoStreamResponse;
import com.isim.dims.entity.CameraCalibration;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.mapper.CameraCalibrationMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.VisualizationPipelineService;
import com.isim.dims.service.VisualizationService;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据可视化 Service 实现
 * <p>
 * 核心业务逻辑：
 * 1. getVideoStreams：查询 camera 类型 SensorStream，返回契约格式 { video_streams: [...] }
 * 2. extractVideoSegment：委派异步视频段提取（@Async 独立 Bean）
 * 3. extractSignalData：委派异步信号数据提取（@Async 独立 Bean）
 * 4. getSignalExtraction：从内存任务存储 + 缓存文件查询结果
 *
 * @feature: visualization
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisualizationServiceImpl implements VisualizationService {

    private final SensorStreamMapper sensorStreamMapper;
    private final DataSliceMapper dataSliceMapper;
    private final CameraCalibrationMapper cameraCalibrationMapper;
    private final VisualizationPipelineService visualizationPipelineService;
    private final StorageAccess storageAccess;
    private final CameraEventConfig cameraEventConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========================================================================
    // 视频流信息查询
    // ========================================================================

    @Override
    public VideoStreamResponse getVideoStreams(String sliceId, String eventName) {
        // 1. 验证切片存在
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }

        // 2. 查询 camera 类型的 SensorStream
        List<SensorStream> cameraStreams = sensorStreamMapper.selectList(
                new LambdaQueryWrapper<SensorStream>()
                        .eq(SensorStream::getSliceId, sliceId)
                        .eq(SensorStream::getSensorCategory, "camera")
                        .orderByAsc(SensorStream::getTopic)
        );

        // 2.5 按触发类型（event_name）过滤摄像头路：
        //     传入 eventName 且在配置中 → 只返回该事件配置的摄像头路（事件配置为"无"→空列表）；
        //     未传 / 未知事件 → fail-open 返回全部
        if (cameraEventConfig.isKnownEvent(eventName)) {
            Set<String> allowedTopics = new HashSet<>(cameraEventConfig.getEventCameraTopics(eventName));
            log.info("Filtering video streams by event_name={}: allowedTopics={}", eventName, allowedTopics);
            cameraStreams = cameraStreams.stream()
                    .filter(s -> allowedTopics.contains(s.getTopic()))
                    .collect(Collectors.toList());
        }

        // 3. 组装响应（按契约格式：{ video_streams: [{sensor_name, topic, video_file, width, height, fps, url, extracted}] }）
        VideoStreamResponse resp = new VideoStreamResponse();

        List<VideoStreamResponse.VideoStreamItem> streamItems = cameraStreams.stream()
                .map(stream -> toVideoStreamItem(stream, sliceId))
                .collect(Collectors.toList());
        resp.setVideoStreams(streamItems);

        return resp;
    }

    // ========================================================================
    // 异步提取视频段
    // ========================================================================

    @Override
    public Map<String, String> extractVideoSegment(String sliceId, ExtractRequest request) {
        // 1. 验证切片存在
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }
        request.setEventName((request.getEventName() != null && !request.getEventName().trim().isEmpty()) ? request.getEventName() : null);

        // 1.5 归档拦截（minio 模式 + storage_type=LOCAL → StorageArchivedException → 410）：
        //     归档切片立即拒绝发起提取任务，不落任务 ERROR
        storageAccess.assertNotArchived(slice);

        // 2. 幂等（P0）：同 slice + video 类型已有 PROCESSING 任务 → 只提取尚未被其覆盖的 topics；
        //    全部已覆盖则直接复用已有任务。前端按 topic 逐路 POST（默认自动选中 4 路），
        //    若按 (sliceId, type) 一刀切复用会把后续相机的 POST 折叠进首路任务导致其永不提取。
        List<String> needExtract = request.getTopics() != null ? new ArrayList<>(request.getTopics()) : new ArrayList<>();

        // 1.8 按触发类型（event_name）收敛摄像头路（camera_event_config.json）：
        //     事件已配置 → 请求 topics 与配置交集；请求未给 topics → 默认提取配置内的全部摄像头路；
        //     事件配置为"无"（如 system_crash）→ 不提取任何摄像头；
        //     未传 / 未知事件 → fail-open 保持原逻辑。点云不在配置内，默认不转换。
        if (cameraEventConfig.isKnownEvent(request.getEventName())) {
            Set<String> allowedTopics = new HashSet<>(cameraEventConfig.getEventCameraTopics(request.getEventName()));
            if (needExtract.isEmpty()) {
                needExtract = new ArrayList<>(allowedTopics);
            } else {
                needExtract.removeIf(t -> !allowedTopics.contains(t));
            }
            log.info("Filtered extract topics by event_name={}: topics={}", request.getEventName(), needExtract);
        }

        String activeTaskId = visualizationPipelineService.findActiveTaskBySlice(sliceId, "video");
        if (activeTaskId != null) {
            Map<String, Object> activeSnapshot = visualizationPipelineService.getTaskSnapshot(activeTaskId);
            Object coveredRaw = activeSnapshot != null ? activeSnapshot.get("topics") : null;
            List<?> coveredTopics = coveredRaw instanceof List ? (List<?>) coveredRaw : Collections.emptyList();
            needExtract.removeIf(coveredTopics::contains);
            if (needExtract.isEmpty()) {
                log.info("Reusing in-flight video extraction task (all topics covered): taskId={}, sliceId={}",
                        activeTaskId, sliceId);
                return buildExtractionResponse(activeTaskId);
            }
            log.info("Video extraction partially covered, extracting missing topics only: sliceId={}, missing={}",
                    sliceId, needExtract);
        }
        needExtract.add("/sensor/camera_right_front/video");

        // 3. 创建任务（createTask 内部同样有幂等检查，双保险；只登记缺失 topics）
        String taskId = visualizationPipelineService.createTask(sliceId, "video", needExtract);

        // 4. 委派异步流水线（@Async 通过 Spring AOP 正常生效；只提取缺失 topics，
        //    避免与进行中任务并发写同一输出文件）
        ExtractRequest extractRequest = new ExtractRequest();
        extractRequest.setTopics(needExtract);
        extractRequest.setStartTs(request.getStartTs());
        extractRequest.setEndTs(request.getEndTs());
        extractRequest.setMaxPointsPerSignal(request.getMaxPointsPerSignal());
        visualizationPipelineService.processExtractVideo(taskId, sliceId, slice.getFilePath(), extractRequest);

        // 5. 返回任务信息
        log.info("Video extraction task created: taskId={}, sliceId={}, topics={}",
                taskId, sliceId, needExtract);
        return buildExtractionResponse(taskId);
    }

    /**
     * 构造异步任务触发响应（202 + { task_id, status }，契约固定格式）
     */
    private Map<String, String> buildExtractionResponse(String taskId) {
        Map<String, String> result = new HashMap<>();
        result.put("task_id", taskId);
        result.put("status", "PROCESSING");
        return result;
    }

    // ========================================================================
    // 异步提取信号数据
    // ========================================================================

    @Override
    public Map<String, String> extractSignalData(String sliceId, ExtractRequest request) {
        // 1. 验证切片存在
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }

        // 1.5 归档拦截（minio 模式 + storage_type=LOCAL → StorageArchivedException → 410）：
        //     归档切片立即拒绝发起提取任务，不落任务 ERROR
        storageAccess.assertNotArchived(slice);

        // 2. 通过 pipeline 服务创建任务
        String taskId = visualizationPipelineService.createTask(sliceId, "signal");

        // 3. 委派异步流水线
        visualizationPipelineService.processExtractSignals(taskId, sliceId, slice.getFilePath(), request);

        // 4. 返回任务信息
        log.info("Signal extraction task created: taskId={}, sliceId={}, topics={}",
                taskId, sliceId, request.getTopics());

        Map<String, String> result = new HashMap<>();
        result.put("task_id", taskId);
        result.put("status", "PROCESSING");
        return result;
    }

    // ========================================================================
    // 查询信号提取结果
    // ========================================================================

    @Override
    public SignalDataResponse getSignalExtraction(String taskId) {
        Map<String, Object> snapshot = visualizationPipelineService.getTaskSnapshot(taskId);
        if (snapshot == null) {
            // 内存中未找到 → 尝试从磁盘恢复（任务已清理或服务重启）
            snapshot = visualizationPipelineService.recoverTaskFromDisk(taskId);
        }
        if (snapshot == null) {
            throw new RuntimeException("SIGNAL_EXTRACTION_TASK_NOT_FOUND: " + taskId);
        }

        SignalDataResponse resp = new SignalDataResponse();
        resp.setTaskId((String) snapshot.get("task_id"));
        resp.setSliceId((String) snapshot.get("slice_id"));
        resp.setStatus((String) snapshot.get("status"));
        resp.setErrorMsg((String) snapshot.get("error_msg"));
        resp.setCreatedAt((String) snapshot.get("created_at"));
        resp.setExpiresAt((String) snapshot.get("expires_at"));

        // 归档拦截（minio 模式 + storage_type=LOCAL → 410 DATA_ARCHIVED）：数据内容读取类端点
        if (snapshot.get("slice_id") != null) {
            storageAccess.assertNotArchived(dataSliceMapper.selectById((String) snapshot.get("slice_id")));
        }

        // 如果已完成，尝试从缓存读取信号数据（result_path：local 为绝对路径，minio 为相对 key）
        if ("READY".equals(snapshot.get("status"))) {
            String resultPath = (String) snapshot.get("result_path");
            if (resultPath != null) {
                try {
                    String content = storageAccess.readString(resultPath);
                    Map<String, Object> resultMap = objectMapper.readValue(content, Map.class);

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> signalsList =
                            (List<Map<String, Object>>) resultMap.get("signals");

                    if (signalsList != null) {
                        List<SignalDataResponse.SignalSeries> series = signalsList.stream()
                                .map(this::toSignalSeries)
                                .collect(Collectors.toList());
                        resp.setSignals(series);
                    }
                } catch (Exception e) {
                    log.warn("Failed to read signal cache file: {}", resultPath, e);
                }
            }
        }

        return resp;
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * SensorStream → VideoStreamItem 转换（按契约格式）
     * <p>
     * 从 sensor_stream 的 metadata JSON 中解析 video_file，
     * 从 frequency_hz 获取帧率，从 camera_calibration 获取宽高。
     * topic 直接取自 sensor_stream.topic；url/extracted 由 topic 清洗生成的
     * 文件名 + 磁盘存在性检查决定。
     */
    private VideoStreamResponse.VideoStreamItem toVideoStreamItem(SensorStream stream, String sliceId) {
        VideoStreamResponse.VideoStreamItem item = new VideoStreamResponse.VideoStreamItem();
        item.setSensorName(stream.getSensorName());
        item.setFps(stream.getFrequencyHz());
        item.setTopic(stream.getTopic());

        // 构造视频静态地址：文件名由 topic 清洗生成（/ 与 . → _，追加 .mp4）
        if (stream.getTopic() != null && sliceId != null) {
            String fileName = stream.getTopic().replace("/", "_").replace(".", "_") + ".mp4";
            String url = "/data/videos/" + sliceId + "/" + fileName;
            item.setUrl(url);
            // 存储中存在该视频 key → 已提取；不存在 → 仍返回拼好的 url，extracted = false
            item.setExtracted(storageAccess.exists(StorageKeys.video(sliceId, fileName)));
        } else {
            item.setExtracted(false);
        }

        // 从 metadata JSON 中解析 video_file
        if (stream.getMetadata() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> metaMap = objectMapper.readValue(stream.getMetadata(), Map.class);
                Object videoFile = metaMap.get("video_file");
                if (videoFile != null) {
                    item.setVideoFile(videoFile.toString());
                }
            } catch (Exception e) {
                log.warn("Failed to parse metadata for sensor={}: {}", stream.getSensorName(), e.getMessage());
            }
        }

        // 从 camera_calibration 获取宽高（CFDI 来源）
        if (stream.getSliceId() != null && stream.getSensorName() != null) {
            try {
                CameraCalibration calib = cameraCalibrationMapper.selectOne(
                        new LambdaQueryWrapper<CameraCalibration>()
                                .eq(CameraCalibration::getSliceId, stream.getSliceId())
                                .eq(CameraCalibration::getSensorName, stream.getSensorName())
                                .last("LIMIT 1")
                );
                if (calib != null) {
                    item.setWidth(calib.getWidth());
                    item.setHeight(calib.getHeight());
                }
            } catch (Exception e) {
                log.warn("Failed to lookup calibration for sensor={}: {}", stream.getSensorName(), e.getMessage());
            }
        }

        return item;
    }

    /**
     * Map → SignalSeries 转换
     */
    private SignalDataResponse.SignalSeries toSignalSeries(Map<String, Object> signalMap) {
        SignalDataResponse.SignalSeries series = new SignalDataResponse.SignalSeries();
        series.setTopic((String) signalMap.get("topic"));
        series.setSignalName((String) signalMap.get("signal_name"));
        series.setDisplayName((String) signalMap.get("display_name"));
        series.setUnit((String) signalMap.get("unit"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) signalMap.get("data");
        if (dataList != null) {
            List<SignalDataResponse.SignalDataPoint> dataPoints = dataList.stream()
                    .map(dp -> {
                        SignalDataResponse.SignalDataPoint point = new SignalDataResponse.SignalDataPoint();
                        point.setTs((String) dp.get("ts"));
                        Object value = dp.get("value");
                        point.setValue(value instanceof Number ? ((Number) value).doubleValue() : null);
                        return point;
                    })
                    .collect(Collectors.toList());
            series.setData(dataPoints);
        }

        return series;
    }

}
