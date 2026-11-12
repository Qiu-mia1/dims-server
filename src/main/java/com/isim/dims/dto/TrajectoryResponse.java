package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * GPS 轨迹响应
 *
 * 对应 GET /v1/data-slices/{sliceId}/trajectory 的响应。
 * OpenAPI schema: TrajectoryResponse
 * @feature: trajectory
 */
@Data
public class TrajectoryResponse {

    /** 切片 UUID */
    private String sliceId;

    /** 轨迹点数组 */
    private List<TrajectoryPoint> trajectory;

    /** 轨迹摘要统计 */
    private TrajectorySummary summary;

    /** 轨迹提取时间，UTC RFC3339 */
    private String extractedAt;

    /**
     * GPS 轨迹点
     */
    @Data
    public static class TrajectoryPoint {

        /** 时间戳，UTC RFC3339 */
        private String ts;

        /** 纬度，WGS84，unit: degrees */
        private Double lat;

        /** 经度，WGS84，unit: degrees */
        private Double lon;

        /** 海拔，unit: m */
        private Double altitude;

        /** 航向角，unit: degrees */
        private Double heading;

        /** 速度，unit: m/s */
        private Double speed;
    }

    /**
     * 轨迹摘要统计
     */
    @Data
    public static class TrajectorySummary {

        /** 轨迹点数 */
        private Integer pointCount;

        /** 总行驶距离，unit: m */
        private Double totalDistanceM;

        /** 起点纬度，WGS84，unit: degrees */
        private Double startLat;

        /** 起点经度，WGS84，unit: degrees */
        private Double startLon;

        /** 终点纬度，WGS84，unit: degrees */
        private Double endLat;

        /** 终点经度，WGS84，unit: degrees */
        private Double endLon;

        /** 平均速度，unit: m/s */
        private Double avgSpeedMps;

        /** 最大速度，unit: m/s */
        private Double maxSpeedMps;
    }
}
