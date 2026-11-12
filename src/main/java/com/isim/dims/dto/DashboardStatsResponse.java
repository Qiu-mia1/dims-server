package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 仪表盘统计概览响应
 *
 * 对应 GET /v1/dashboard/stats 的响应。
 * OpenAPI schema: DashboardStats
 * @feature: dashboard
 */
@Data
public class DashboardStatsResponse {

    /** 系统切片总数 */
    private Long totalSlices;

    /** 今日（UTC）上传切片数 */
    private Long todayUploads;

    /** 所有原始 bag 文件总字节数 */
    private Long totalDataSize;

    /** 人类可读的总数据量，如 "80.00 GB" */
    private String totalDataSizeHuman;

    /** 传感器分布统计 */
    private List<SensorDistributionItem> sensorDistribution;

    /** 统计更新时间，UTC RFC3339 */
    private String updatedAt;

    /**
     * 传感器分布统计项
     */
    @Data
    public static class SensorDistributionItem {

        /** 传感器类型，如 "camera"、"lidar" */
        private String category;

        /** 含有该传感器的切片数 */
        private Long count;

        /** 占比（%），如 100.0 */
        private Double percentage;
    }
}
