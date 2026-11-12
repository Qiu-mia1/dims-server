package com.isim.dims.service;

import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.SignalDataResponse;
import com.isim.dims.dto.VideoStreamResponse;

import java.util.Map;

/**
 * 数据可视化 Service 接口
 *
 * 负责视频流信息查询和异步信号数据提取。
 * - 视频流：查询 sensor_stream 表 camera 类型流，检查缓存
 * - 信号数据：异步调用 Python extract_signals.py 提取
 * @feature: visualization
 */
public interface VisualizationService {

    /**
     * 查询切片的视频流信息
     *
     * 返回切片中 camera 类型的 SensorStream 信息，
     * 以及当前缓存的视频段元数据。
     *
     * <p>传入 eventName（触发类型，即 meta.event_name）时，仅返回
     * {@link CameraEventConfig} 中该事件配置的摄像头路；未传或未知事件
     * 返回全部摄像头（fail-open）。点云不在返回/转换范围内（点云默认不转换）。</p>
     *
     * @param sliceId   切片 UUID
     * @param eventName 触发类型（可选，meta.event_name）
     * @return 视频流信息响应
     * @throws RuntimeException 如果切片不存在
     */
    VideoStreamResponse getVideoStreams(String sliceId, String eventName);

    /**
     * 异步提取视频段
     *
     * @param sliceId 切片 UUID
     * @param request 提取请求（topics、时间窗口）
     * @return 包含 task_id 和 status 的 Map
     * @throws RuntimeException 如果切片不存在
     */
    Map<String, String> extractVideoSegment(String sliceId, ExtractRequest request);

    /**
     * 异步提取信号时间序列数据
     *
     * @param sliceId 切片 UUID
     * @param request 提取请求（topics、时间窗口、maxPointsPerSignal）
     * @return 包含 task_id 和 status 的 Map
     * @throws RuntimeException 如果切片不存在
     */
    Map<String, String> extractSignalData(String sliceId, ExtractRequest request);

    /**
     * 查询信号提取任务结果
     *
     * @param taskId 任务 UUID
     * @return 信号提取任务结果（status=READY 时含信号数据）
     */
    SignalDataResponse getSignalExtraction(String taskId);
}
