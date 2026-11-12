package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 提取任务详情
 *
 * 对应 OpenAPI schema: ReplayExtractionTask。
 * required: [task_id, slice_id, type, status, created_at]
 * @feature: replay
 */
@Data
public class ReplayExtractionTaskResponse {

    /** 任务 UUID */
    private String taskId;

    /** 切片 UUID */
    private String sliceId;

    /** obstacles | messages */
    private String type;

    /** 目标 topic 列表 */
    private List<String> topics;

    /** 提取窗口起点，UTC RFC3339 */
    private String startTs;

    /** 提取窗口终点，UTC RFC3339 */
    private String endTs;

    /** PENDING|RUNNING|SUCCESS|FAILED（ReplayTaskStatus 枚举） */
    private String status;

    /** 进度 0-100 */
    private Integer progress;

    /** 失败原因/进度说明（失败时非空） */
    private String message;

    /** 产物路径摘要（如分段数、覆盖区间），SUCCESS 时有值 */
    private Map<String, Object> outputSummary;

    /** 任务创建时间，UTC RFC3339 */
    private String createdAt;

    /** 任务结束时间，UTC RFC3339 */
    private String finishedAt;
}
