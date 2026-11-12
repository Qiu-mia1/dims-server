package com.isim.dims.dto;

import lombok.Data;

/**
 * 提取任务创建响应（202）
 *
 * 对应 OpenAPI schema: ReplayExtractionResponse。
 * required: [task_id, status]
 * @feature: replay
 */
@Data
public class ReplayExtractionResponse {

    /** 进行中任务去重时返回同一 task_id */
    private String taskId;

    /** PENDING|RUNNING|SUCCESS|FAILED（ReplayTaskStatus 枚举），默认 PENDING */
    private String status;
}
