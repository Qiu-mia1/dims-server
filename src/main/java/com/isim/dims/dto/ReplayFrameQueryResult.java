package com.isim.dims.dto;

import lombok.Data;

/**
 * 回放帧查询结果（Controller 路由用，泛型化 ObstacleQueryResult）
 *
 * <p>缓存命中 → frame 非空，返回 200；
 * 缓存未命中 → frame 为 null、taskResponse 非空，返回 202 + { task_id, status }
 * （status 透传任务真实状态：进行中同参去重可能为 RUNNING）。</p>
 *
 * <p>供 obstacles（ObstacleFrameResponse）、perception（PerceptionFrameResponse）、
 * map（MapFrameResponse）三类帧读取复用。</p>
 * @feature: replay
 */
@Data
public class ReplayFrameQueryResult<T> {

    /** 缓存命中的帧（200） */
    private T frame;

    /** 缓存未命中时创建/复用的提取任务（202） */
    private ReplayExtractionResponse taskResponse;
}
