package com.isim.dims.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * MXF 行驶轨迹监控聚合响应
 *
 * <p>对应 {@code GET /v1/mxf/trajectory/monitor}。
 * 每个车辆（VIN）按时间序给出其全部 MXF 轨迹段（每个 MXF 包 = 一段行驶轨迹）、
 * 轨迹点明细，以及各触发事件关联的轨迹点（选中事件单独显示轨迹线）。
 *
 * <p>时间均为北京时间（yyyy-MM-dd HH:mm:ss）；经纬度为加偏坐标（GCJ-02），
 * 与高德底图直接对齐，前端无需再做 WGS84 转换。
 *
 * <p>JSON 字段为全局 snake_case（application.yml jackson.property-naming-strategy）。
 *
 * @feature: mxf
 */
@Data
@Builder
public class MxfTrajectoryMonitorResponse {

    /** 查询窗口起点（北京时间，回显） */
    private String timeStart;

    /** 查询窗口终点（北京时间，回显） */
    private String timeEnd;

    /** 服务端生成时间（北京时间） */
    private String generatedAt;

    private Summary summary;

    /** 车辆列表（按窗口内最早触发时间排序） */
    private List<VehicleTrack> vehicles;

    /**
     * 汇总 KPI（行驶轨迹监控页顶部卡片）
     */
    @Data
    @Builder
    public static class Summary {
        /** 有轨迹点的车辆数 */
        private int vehicleCount;
        /** 窗口内车辆总数（含无轨迹点包） */
        private int totalVehicleCount;
        /** 窗口内 MXF 包数（= 轨迹段数） */
        private long packetCount;
        /** 轨迹总里程 km（按轨迹点折线累加） */
        private double totalDistanceKm;
        /** 轨迹点总数 */
        private long totalPointCount;
        /** 事件总数（触发事件发生次数：包 × 事件名） */
        private long totalEventCount;
    }

    /**
     * 单车轨迹聚合
     */
    @Data
    @Builder
    public static class VehicleTrack {
        /** 展示 VIN（优先 vin_md5_mapping 反查，未命中回退 meta 值） */
        private String vin;
        /** VIN 来源：mapping=映射表反查 / meta=meta 原始值 */
        private String vinSource;
        /** 轨迹段数（MXF 包数） */
        private int packetCount;
        /** 轨迹点数 */
        private long pointCount;
        /** 总里程 km */
        private double distanceKm;
        /** 总行驶时长 h */
        private double durationHours;
        /** 平均车速 km/h */
        private double avgSpeedKmh;
        /** 最高车速 km/h（相邻轨迹点距离/时间差的最大值） */
        private double maxSpeedKmh;
        /** 最早轨迹时间（北京时间） */
        private String firstTime;
        /** 最晚轨迹时间（北京时间） */
        private String lastTime;
        /** 轨迹段列表（每个 MXF 包一段，按时间序） */
        private List<Segment> segments;
        /** 事件列表（同名事件按时间排序给 index = 第 N 次） */
        private List<EventItem> events;
    }

    /**
     * 轨迹段：一个 MXF 包对应一段行驶轨迹
     */
    @Data
    @Builder
    public static class Segment {
        private String packetId;
        /** MXF 文件名 */
        private String fileName;
        /** 触发时间（北京时间） */
        private String triggerTime;
        /** 段起始时间（北京时间） */
        private String startTime;
        /** 段结束时间（北京时间） */
        private String endTime;
        /** 轨迹点数（未抽稀全量计数） */
        private long pointCount;
        /**
         * 绘制模式：line=完整轨迹点画线；points=只有起止点画点。
         * 点数 >= 3 为 line，否则为 points（需求 5）。
         */
        private String drawMode;
        /** 段里程 km */
        private double distanceKm;
        /** 轨迹点列表（已抽稀，按时间序） */
        private List<PointItem> points;
    }

    /**
     * 轨迹点
     */
    @Data
    @Builder
    public static class PointItem {
        /** 日志时间（北京时间） */
        private String ts;
        /** 加偏纬度 */
        private double lat;
        /** 加偏经度 */
        private double lon;
        /** 所属触发事件名（包级点为 null） */
        private String eventName;
        /** 点类型：pg | enter | exit | cur */
        private String pointType;
    }

    /**
     * 触发事件关联轨迹：MXF 内多个触发事件，选中单个事件单独显示对应轨迹线
     */
    @Data
    @Builder
    public static class EventItem {
        /** 事件名（如 aeb / nop / take_over） */
        private String eventName;
        /** 展示标题 */
        private String title;
        /** 第 N 次（同车同名事件按时间排序，从 1 开始） */
        private int index;
        /** 首次触发时间（北京时间） */
        private String time;
        /** 所属 MXF 包 id */
        private String packetId;
        /** 该事件轨迹点数 */
        private long pointCount;
        /** line | points（同 Segment 规则） */
        private String drawMode;
        /** 该事件轨迹点列表（已抽稀，按时间序） */
        private List<PointItem> points;
    }
}
