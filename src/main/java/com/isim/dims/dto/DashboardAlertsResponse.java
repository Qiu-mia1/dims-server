package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 仪表盘质量告警响应
 *
 * 对应 GET /v1/dashboard/alerts 的响应。
 * 告警类型：
 *   missing_calibration  — 标定缺失（CFDI 链路切片（cfdi_meta 有记录）但 camera_calibration 表无记录）
 *   vin_empty            — VIN 为空（cfdi_meta.vin 为空）
 *   incomplete_sensors   — 传感器不完整（sensor_stream 数 < 8）
 *   status_error         — 处理失败（data_slice.status = 'ERROR'）
 * 字段命名对齐 OpenAPI 契约 snake_case。
 * @feature: dashboard
 */
@Data
public class DashboardAlertsResponse {

    /** 告警列表 */
    private List<AlertItem> alerts;

    @Data
    public static class AlertItem {

        /** 告警类型标识：missing_calibration | vin_empty | incomplete_sensors | status_error */
        private String type;

        /** 告警中文标签，如 "标定缺失" */
        private String label;

        /** 受影响的切片数量 */
        private Long count;

        /** 受影响的切片 ID 列表（仅 missing_calibration 携带） */
        @JsonProperty("slice_ids")
        private List<String> sliceIds;
    }
}
