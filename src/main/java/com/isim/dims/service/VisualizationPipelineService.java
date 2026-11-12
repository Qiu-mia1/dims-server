package com.isim.dims.service;

import com.isim.dims.dto.ExtractRequest;

import java.util.List;
import java.util.Map;

/**
 * 可视化数据异步提取流水线 Service
 *
 * 独立 Bean 确保 @Async 通过 Spring AOP 代理正常生效。
 * 负责：
 * - GPS 轨迹提取（Python extract_gps_trajectory.py）
 * - 视频段提取（Python extract_video_segment.py）
 * - 信号数据提取（Python extract_signals.py）
 * @feature: visualization
 */
public interface VisualizationPipelineService {

    /**
     * 创建提取任务并返回 taskId
     *
     * <p>幂等：同 slice + 同类型已有 PROCESSING 任务时直接复用已有 taskId，
     * 防止重复 POST 触发多个异步任务并发写同一输出文件。</p>
     *
     * @param sliceId 切片 UUID
     * @param type    任务类型: trajectory|video|signal
     * @return 任务 UUID
     */
    String createTask(String sliceId, String type);

    /**
     * 创建提取任务并返回 taskId（携带本次请求的 topic 列表）
     *
     * <p>在 {@link #createTask(String, String)} 幂等基础上，进一步要求已有
     * PROCESSING 任务的 topics 覆盖本次请求的 topics 才复用；未覆盖（如
     * 前端按 topic 逐路 POST）时创建新任务，避免多路相机并发 POST 被
     * 折叠成单 topic 任务导致其余相机永不提取。</p>
     *
     * @param sliceId 切片 UUID
     * @param type    任务类型: trajectory|video|signal
     * @param topics  本次请求计划提取的 topic 列表（可空，空则退化为按 type 幂等）
     * @return 任务 UUID
     */
    String createTask(String sliceId, String type, List<String> topics);

    /**
     * 查询任务状态快照
     *
     * @param taskId 任务 UUID
     * @return 任务状态 Map，包含 task_id, slice_id, status, progress 等
     */
    Map<String, Object> getTaskSnapshot(String taskId);

    /**
     * 查询视频提取任务状态快照
     *
     * 从 taskStore 中取出视频提取任务的状态信息。
     * 与 getTaskSnapshot 类似，但语义绑定到视频提取场景。
     *
     * @param taskId 任务 UUID
     * @return 任务状态 Map，未找到时返回 null
     */
    Map<String, Object> getVideoExtractionTask(String taskId);

    /**
     * 查找指定切片正在进行的提取任务
     *
     * <p>用于防止轨迹查询接口在缓存缺失时重复触发提取
     * （多个并发请求/轮询场景下复用已在进行中的任务）。</p>
     *
     * @param sliceId     切片 UUID
     * @param extractType 任务类型: trajectory|video|signal，null 表示任意类型
     * @return 进行中任务的任务 ID；不存在时返回 null
     */
    String findActiveTaskBySlice(String sliceId, String extractType);

    /**
     * 异步执行 GPS 轨迹提取
     *
     * @param taskId       任务 ID
     * @param sliceId      切片 UUID
     * @param bagFilePath  bag 文件路径
     * @param request      提取请求参数
     */
    void processExtractTrajectory(String taskId, String sliceId, String bagFilePath,
                                  ExtractRequest request);

    /**
     * 异步执行视频段提取
     *
     * @param taskId       任务 ID
     * @param sliceId      切片 UUID
     * @param bagFilePath  bag 文件路径
     * @param request      提取请求参数
     */
    void processExtractVideo(String taskId, String sliceId, String bagFilePath,
                             ExtractRequest request);

    /**
     * 异步执行信号数据提取
     *
     * @param taskId       任务 ID
     * @param sliceId      切片 UUID
     * @param bagFilePath  bag 文件路径
     * @param request      提取请求参数
     */
    void processExtractSignals(String taskId, String sliceId, String bagFilePath,
                               ExtractRequest request);

    /**
     * 从磁盘恢复任务元数据
     *
     * 当任务状态不在内存中时（已清理或服务重启），从持久化的任务元数据文件恢复。
     * 恢复后会自动重新加载到内存存储中。
     *
     * @param taskId 任务 UUID
     * @return 任务状态 Map，包含 task_id, slice_id, status, progress 等；未找到时返回 null
     */
    Map<String, Object> recoverTaskFromDisk(String taskId);
}
