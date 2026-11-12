package com.isim.dims.dto;

import lombok.Data;

/**
 * 障碍物查询结果（Controller 路由用）
 *
 * <p>缓存命中 → frame 非空，返回 200；
 * 缓存未命中 → frame 为 null、taskResponse 非空，返回 202 + { task_id, status }
 * （status 透传任务真实状态：进行中同参去重可能为 RUNNING）。</p>
 * @feature: visualization
 */
@Data
public class ObstacleQueryResult {

    /** 缓存命中的障碍帧（200） */
    private ObstacleFrameResponse frame;

    /** 缓存未命中时创建/复用的提取任务（202） */
    private ReplayExtractionResponse taskResponse;
}
