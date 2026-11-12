package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 看板地图响应：最近 N 小时内每辆车（VIN）最新一条切片的定位轨迹。
 *
 * <p>取数口径：{@code data_slice} 按 vin 分组取 24 小时内 trigger_time 最新的一条
 * READY 切片，轨迹点来自该切片回放产物（{@code replay/.../relay_plus/rosdata.json}）
 * 的 {@code egoPoseMessages} —— 即 /mla/egopose 的经纬度结果（WGS-84，度）。</p>
 *
 * <p>JSON 字段为全局 snake_case（application.yml jackson.property-naming-strategy），
 * 与前端 {@code api/mxf.js#getMxfVehicleTracks} 一一对应。</p>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleTrackResponse {

    /** 查询窗口：最近 N 小时 */
    private Integer hours;

    /** 生成时间，北京时间（yyyy-MM-dd HH:mm:ss，东八区） */
    private String generatedAt;

    /** 命中车辆数（含无定位数据的车辆） */
    private Integer vehicleCount;

    /** 车辆轨迹列表（按切片触发时间倒序） */
    private List<VehicleTrack> vehicles;

    /**
     * 单车轨迹
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VehicleTrack {

        /** VIN 码 */
        private String vin;

        /** 切片 UUID（data_slice.id） */
        private String sliceId;

        /** 切片名称 */
        private String sliceName;

        /** 切片触发时间，北京时间（yyyy-MM-dd HH:mm:ss，东八区；DB 存 UTC，出参已换算） */
        private String triggerTime;

        /** 数据来源提供方（MMT / ZYT / Hirain / ...） */
        private String sourceType;

        /** 事件类型（data_slice.event_name） */
        private String eventName;

        /** 轨迹点数（抽稀后） */
        private Integer pointCount;

        /** 轨迹数据来源：ego_pose（/mla/egopose 经纬度）| gps_trajectory（GPS 轨迹兜底） */
        private String dataSource;

        /** 末点纬度（主车最后位置），无定位数据时为空 */
        private Double lastLat;

        /** 末点经度（主车最后位置），无定位数据时为空 */
        private Double lastLon;

        /** 轨迹点（lat/lon，WGS-84 度；已抽稀，最多 300 点） */
        private List<TrackPoint> points;
    }

    /**
     * 轨迹点（WGS-84 经纬度，度）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrackPoint {

        /** 纬度，WGS84，unit: degrees */
        private Double lat;

        /** 经度，WGS84，unit: degrees */
        private Double lon;
    }
}