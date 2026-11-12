package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 一帧 ddld 地图数据（窗口内最近一帧聚合，landmark_vis）
 *
 * <p>数据源：/momenta_public/ddld/landmark_vis（8.5Hz），消息以
 * std_msgs/msg/Header 录制，业务负载内嵌 frame_id JSON（landmarks）。</p>
 *
 * <p>坐标系（契约 §7 标注，重要）：ddld 地图为 <strong>ego 局部系</strong>——
 * 原点=自车，x 前 y 左 z 上（unit: m），实测范围 x∈[-50,110]、y∈[-32,32]；
 * 与 smooth_object（全局系 WGS84 投影）坐标系不同，不得直接叠加。
 * line_width 与点位坐标 unit 均为 m。</p>
 *
 * <p>时间：time/time_ns 为 bag 时间戳（epoch）；frame_id 内 header.stamp 为
 * 上电相对时间，仅调试参考，不用于同步。</p>
 * @feature: visualization
 */
@Data
public class MapFrameResponse {

    /** 实际返回帧的消息时间（bag 时间戳），UTC RFC3339 */
    private String time;

    /** 实际返回帧的消息时间，bag 时间戳纳秒整数，unit: ns（与 time 同一时刻） */
    private Long timeNs;

    /** 坐标系：ego（原点=自车，x 前 y 左 z 上） */
    private String frame = "ego";

    /** 长度单位：m */
    private String unit = "m";

    /** 车道线（lane_boundaries），可为空列表 */
    private List<MapLine> laneBoundaries;

    /** 道路边界（road_boundaries），可为空列表 */
    private List<MapLine> roadBoundaries;

    /** 停止线（stop_lines），可为空列表 */
    private List<MapLine> stopLines;

    /**
     * 地图线要素（ego 局部系，unit: m）
     */
    @Data
    public static class MapLine {

        /**
         * 线型（按要素类别映射的枚举名，raw 数值保留在 typeValue）：
         * lane: unknown|dashed|solid|double_solid|double_dashed|...
         * road: unknown|none|normal|virtual|curb|guardrail|...
         * stop: unknown|normal|left_waiting_area|...
         */
        private String type;

        /** 原始枚举值（msgdef 数值，与 type 对应） */
        private Integer typeValue;

        /** 车道线颜色（仅 lane_boundaries 有）：unknown|white|yellow|orange|... */
        private String color;

        /** 车道线颜色原始枚举值（仅 lane_boundaries 有） */
        private Integer colorValue;

        /** 车道线宽度（仅 lane_boundaries 有），unit: m */
        private Double lineWidthM;

        /** 折线点序列 [{x, y}]，unit: m；端点数量不固定 */
        private List<MapPoint> points;
    }

    /** 地图点（ego 局部系，unit: m） */
    @Data
    public static class MapPoint {

        /** 点坐标 x，unit: m */
        private Double x;

        /** 点坐标 y，unit: m */
        private Double y;
    }
}
