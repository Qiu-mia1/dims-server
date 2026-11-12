package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 导入任务响应（含状态历史）
 *
 * 对应 GET /v1/tasks/{taskId} 的响应。
 * 比 IngestionTask 实体多 status_history 数组，
 * 避免将内部实体直接暴露为 API 响应。
 * @feature: ingestion
 */
@Data
public class TaskResponse {

    /** 导入任务 UUID */
    @JsonProperty("task_id")
    private String taskId;

    /** 关联的数据切片 UUID */
    @JsonProperty("slice_id")
    private String sliceId;

    /** 当前状态：UPLOADING|CONVERTING|PARSING|TAGGING|READY|ERROR */
    private String status;

    /** 进度 0-100 */
    private Integer progress;

    /** 错误信息（对齐 FRONTEND-REDESIGN.md 契约使用 error_message） */
    @JsonProperty("error_message")
    private String errorMsg;

    /** 状态变更历史 */
    @JsonProperty("status_history")
    private List<StatusHistoryItem> statusHistory;

    @Data
    public static class StatusHistoryItem {

        /** 状态 */
        private String status;

        /** 状态变更时间，UTC RFC3339 */
        private String timestamp;
    }
}
