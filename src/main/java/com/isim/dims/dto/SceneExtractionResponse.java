package com.isim.dims.dto;

import lombok.Data;

/**
 * 场景提取任务创建响应（202）
 *
 * 对应 OpenAPI schema: SceneExtractionResponse。
 * required: [task_id, status]
 *
 * 与回放提取（ReplayExtractionResponse）同构：场景提取为全量任务
 * （对切片完整 bag 做场景检测），无请求体、无参数，触发即返回任务句柄；
 * 前端轮询 GET /v1/scene-extractions/{taskId} 获取进度。
 *
 * 语义约定：同切片已有进行中任务时返回 409 SCENE_EXTRACTION_IN_PROGRESS
 * （不回传旧 task_id——与回放提取的同参去重不同，场景提取无参数可去重）。
 *
 * @feature: ingestion
 */
@Data
public class SceneExtractionResponse {

    /** 提取任务 UUID */
    private String taskId;

    /** PENDING|RUNNING|SUCCESS|FAILED（SceneExtractionStatus 枚举），默认 PENDING */
    private String status;
}
