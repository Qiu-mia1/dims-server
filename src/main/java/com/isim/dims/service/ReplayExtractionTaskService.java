package com.isim.dims.service;

import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.dto.ReplayExtractionResponse;
import com.isim.dims.dto.ReplayExtractionTaskResponse;

/**
 * 回放提取任务管理 Service
 *
 * <p>职责（docs/REPLAY-2D-PLAN.md §8）：
 * <ul>
 *   <li>任务状态机：PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试 1 次）</li>
 *   <li>同参去重：同一 (slice_id, type, topics, start_ts, end_ts) 进行中任务返回同一 task_id</li>
 *   <li>按 slice 串行：同切片同时只允许 1 个提取任务，跨参数并发抛 REPLAY_EXTRACT_IN_PROGRESS</li>
 *   <li>产物路径入库：SUCCESS 后回写 output_path / message / finished_at</li>
 * </ul>
 * 异步执行委派给 {@link ReplayExtractionPipelineService}（独立 Bean，@Async 通过 Spring AOP 生效）。
 * </p>
 * @feature: replay
 */
public interface ReplayExtractionTaskService {

    /**
     * 创建（或复用）回放提取任务
     *
     * @param sliceId 切片 UUID（不存在抛 SLICE_NOT_FOUND）
     * @param request 提取请求（type 必填；messages 时 topics 必填；窗口默认切片全区间）
     * @return 202 响应体 { task_id, status }；进行中同参任务返回同一 task_id
     * @throws RuntimeException
     *   REPLAY_EXTRACT_IN_PROGRESS   — 同切片已有其他参数任务进行中（按 slice 串行）
     *   REPLAY_EXTRACT_RETRY_EXCEEDED — 同参任务已 FAILED 超过 1 次（重试超限，永久失败，409）
     *   REPLAY_TOPIC_NOT_FOUND        — 显式传入 topic 不在切片 topic 清单（404）
     *   INVALID_PARAMETER             — type/topics 缺失/时间窗口非法
     *
     * @see ReplayExtractionPipelineService type 取值 obstacles|messages|perception|map
     */
    ReplayExtractionResponse createTask(String sliceId, ReplayExtractionRequest request);

    /**
     * 查询任务详情
     *
     * @param taskId 任务 UUID
     * @return 任务详情
     * @throws RuntimeException TASK_NOT_FOUND — 任务不存在
     */
    ReplayExtractionTaskResponse getTask(String taskId);
}
