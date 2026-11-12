package com.isim.dims.service;

import com.isim.dims.dto.DetectedSceneDTO;
import com.isim.dims.dto.SceneExtractionResponse;
import com.isim.dims.dto.SceneExtractionTaskResponse;
import com.isim.dims.dto.SceneListResponse;
import com.isim.dims.entity.SceneExtractionTask;

/**
 * 场景提取流水线 Service
 *
 * <p>职责（DEVELOPMENT.md §5.6 / docs/scene_extraction_design.md）：</p>
 * <ul>
 *   <li>触发全量场景提取：对切片完整 bag 依次执行 extract_gps_trajectory →
 *       extract_perception → bag_to_unified_json → scene_detector，
 *       结果持久化到 detected_scene 表</li>
 *   <li>任务状态机：PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试 1 次）</li>
 *   <li>按 slice 串行：同切片同时只允许 1 个进行中任务（无参数可去重，跨参并发抛
 *       SCENE_EXTRACTION_IN_PROGRESS）</li>
 *   <li>查询：任务详情 / 切片场景分页列表 / 场景详情</li>
 * </ul>
 * 异步执行在 {@code processExtraction}（独立 Bean @Async，AOP 代理生效）。
 * @feature: ingestion
 */
public interface SceneExtractionPipelineService {

    /**
     * 触发切片场景提取任务
     *
     * @param sliceId 切片 UUID（不存在抛 SLICE_NOT_FOUND）
     * @return 202 响应体 { task_id, status }
     * @throws RuntimeException
     *   SCENE_EXTRACTION_IN_PROGRESS   — 同切片已有进行中任务（PENDING|RUNNING，409）
     *   SCENE_EXTRACTION_RETRY_EXCEEDED — 同切片 FAILED 次数 ≥ 2（重试超限，409）
     */
    SceneExtractionResponse triggerExtraction(String sliceId);

    /**
     * 异步执行场景提取流水线（@Async("sceneExtractExecutor")）
     *
     * <p>由 {@link #triggerExtraction} 经 @Lazy 自引用代理调用（同类自调用
     * 会绕过 AOP 代理导致 @Async 失效）。仅 PENDING 任务可进入 RUNNING
     * （幂等抢占，并发 dispatch 只执行一次）。</p>
     *
     * @param task 已落库的 PENDING 任务
     */
    void processExtraction(SceneExtractionTask task);

    /**
     * 查询任务详情
     *
     * @param taskId 任务 UUID
     * @return 任务详情
     * @throws RuntimeException TASK_NOT_FOUND — 任务不存在
     */
    SceneExtractionTaskResponse getTask(String taskId);

    /**
     * 查询切片已检测场景列表（分页，按 start_time 升序）
     *
     * @param sliceId  切片 UUID
     * @param page     页码，默认 1
     * @param pageSize 每页条数，默认 50，最大 500
     * @return 分页场景列表
     */
    SceneListResponse listScenes(String sliceId, int page, int pageSize);

    /**
     * 查询单个场景详情
     *
     * @param sceneId 场景 UUID
     * @return 场景详情
     * @throws RuntimeException SCENE_NOT_FOUND — 场景不存在
     */
    DetectedSceneDTO getScene(String sceneId);

    /**
     * 查询切片最新提取任务
     *
     * <p>前端页面刷新后恢复提取状态（无需依赖 localStorage）。
     * 按 created_at DESC 取最新一条（含 FAILED/SUCCESS/RUNNING），
     * 无任何提取记录时返回 null。</p>
     *
     * @param sliceId 切片 UUID
     * @return 最新任务详情；无记录返回 null
     */
    SceneExtractionTaskResponse getLatestTaskBySliceId(String sliceId);

    /**
     * 读取过程文件内容
     *
     * @param sliceId 切片 UUID（内部查最新任务）
     * @param path    文件绝对路径（需在任务 intermediate_paths / output_path 中）
     * @return 文件内容字符串
     * @throws RuntimeException NO_EXTRACTION_TASK / FILE_NOT_ALLOWED / FILE_READ_ERROR
     */
    String readProcessFile(String sliceId, String path);
}
