package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 导出任务摘要项（列表场景）
 *
 * 对应 GET /v1/exports 响应 items 中的单个元素。
 * 继承 {@link ExportResponse} 的公共字段（export_id / slice_id / format / status /
 * file_size / download_url / expires_at / created_at，由全局 SNAKE_CASE 策略序列化），
 * 仅补充列表场景的额外字段：slice_name / start_ts / end_ts / updated_at。
 * @feature: export
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExportTaskSummary extends ExportResponse {

    /** 关联的切片名称（LEFT JOIN data_slice 补填） */
    @JsonProperty("slice_name")
    private String sliceName;

    /** 导出时间窗口起点，UTC RFC3339 */
    @JsonProperty("start_ts")
    private String startTs;

    /** 导出时间窗口终点，UTC RFC3339 */
    @JsonProperty("end_ts")
    private String endTs;

    /** 导出任务最近更新时间，UTC RFC3339，null 表示尚未更新 */
    @JsonProperty("updated_at")
    private String updatedAt;
}
