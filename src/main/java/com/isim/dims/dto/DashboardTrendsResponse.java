package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 仪表盘时间趋势响应
 *
 * 对应 GET /v1/dashboard/trends?days=30 的响应。
 * 返回每日上传数量和数据量趋势。
 * 字段命名对齐 OpenAPI 契约 snake_case。
 * @feature: dashboard
 */
@Data
public class DashboardTrendsResponse {

    /** 趋势数据列表 */
    private List<TrendItem> trends;

    @Data
    public static class TrendItem {

        /** 日期，格式 "yyyy-MM-dd"，如 "2026-07-01" */
        private String date;

        /** 当日上传切片数 */
        @JsonProperty("upload_count")
        private Long uploadCount;

        /** 当日上传文件总字节数 */
        @JsonProperty("total_size_bytes")
        private Long totalSizeBytes;
    }
}
