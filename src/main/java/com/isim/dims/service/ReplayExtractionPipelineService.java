package com.isim.dims.service;

import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.entity.ReplayExtractionTask;

/**
 * 回放提取异步流水线 Service
 *
 * <p>独立 Bean 确保 @Async 通过 Spring AOP 代理正常生效
 * （与 ExportPipelineService / VisualizationPipelineService 同模式）。</p>
 *
 * <p>Python 调用约定（与 dims-python 脚本的契约，Algorithm Agent 并行实现）：</p>
 * <ul>
 *   <li>{@code extract_obstacles.py}：
 *       输入 { bag_path, topics[], start_ns, end_ns, output_dir, calibration_path? }；
 *       输出 segments/*.json + index.json；stdout { success, segments, obstacle_count, calibrated }</li>
 *   <li>{@code extract_raw_messages.py}：
 *       输入 { bag_path, topic, start_ns, end_ns, output_path, limit }；
 *       输出 JSONL（每行 { seq, stamp_ns, stamp, data, header_stamp_ns?, sensor_timestamp_us? }）；
 *       stdout { success, count, output_path }</li>
 * </ul>
 * @feature: replay
 */
public interface ReplayExtractionPipelineService {

    /**
     * 异步执行提取任务（@Async）
     *
     * <p>同一 taskId 只允许执行一次（PENDING → RUNNING 幂等抢占，重复 dispatch 直接返回）；
     * 成功后回写 SUCCESS + output_path + message；失败回写 FAILED + 错误信息。</p>
     *
     * @param task      已落库的 PENDING 任务实体（传实体避免事务提交竞态）
     * @param bagPath   切片原始 bag 文件路径
     * @param request   归一化后的提取请求
     */
    void processExtraction(ReplayExtractionTask task, String bagPath, ReplayExtractionRequest request);
}
