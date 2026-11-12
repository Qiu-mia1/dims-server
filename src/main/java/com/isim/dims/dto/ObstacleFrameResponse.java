package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 障碍物帧（窗口内最近一帧聚合）
 *
 * 对应 OpenAPI schema: ObstacleFrameResponse。
 * required: [time, time_ns, frame, unit, calibrated, obstacles]
 * @feature: visualization
 */
@Data
public class ObstacleFrameResponse {

    /** 实际返回帧的消息时间（bag 时间戳，可能略早于请求 time，落在窗口内），UTC RFC3339 */
    private String time;

    /** 实际返回帧的消息时间，bag 时间戳纳秒整数，unit: ns（与 time 同一时刻） */
    private Long timeNs;

    /** 坐标系：ego（原点=车体后轴中心，x 前 y 左 z 上） */
    private String frame = "ego";

    /** 长度单位：m（速度单位 m/s 见各字段） */
    private String unit = "m";

    /**
     * 是否经标定换算：true=各传感器系对齐到统一 ego 系；
     * false=原始消息本身已在 car 系、直接透传（标定缺失），前端仅展示
     */
    private boolean calibrated;

    /** 目标列表（单帧 ≤ 200） */
    private List<ObstacleTarget> obstacles;

    /**
     * 障碍目标（雷达/超声波）。坐标统一 ego 系，unit: m；速度 unit: m/s。
     *
     * 对应 OpenAPI schema: ObstacleTarget。
     * required: [sensor, type, id, x, y, status]
     */
    @Data
    public static class ObstacleTarget {

        /** 来源传感器：雷达用 sensor_tag（如 "Bosch_mrr_Front"），超声波用 topic 段名（如 "ultrasonic"） */
        private String sensor;

        /** radar|ultrasonic（ObstacleType 枚举） */
        private String type;

        /** 目标标识（雷达 object_id / 超声波 meta.seq，尽力跨帧稳定；无稳定 ID 可为空串） */
        private String id;

        /** 目标坐标 x（ego 系），unit: m */
        private Double x;

        /** 目标坐标 y（ego 系），unit: m */
        private Double y;

        /** 目标坐标 z（ego 系），unit: m，可省略 */
        private Double z;

        /** 相对速度 x（雷达），unit: m/s */
        private Double vx;

        /** 相对速度 y（雷达），unit: m/s */
        private Double vy;

        /** 雷达散射截面（仅 Conti 通道），unit: dBsm；Bosch 通道为 null */
        private Double rcs;

        /** 目标长度（仅 Conti 通道），unit: m */
        private Double lengthM;

        /** 目标宽度（仅 Conti 通道），unit: m */
        private Double widthM;

        /** 超声波线段端点起点 x（LINE_SEGMENT 类型），unit: m */
        private Double startX;

        /** 超声波线段端点起点 y（LINE_SEGMENT 类型），unit: m */
        private Double startY;

        /** 超声波线段端点终点 x（LINE_SEGMENT 类型），unit: m */
        private Double endX;

        /** 超声波线段端点终点 y（LINE_SEGMENT 类型），unit: m */
        private Double endY;

        /** 超声波点状目标 x（POINT/KEYPOINT 类型），unit: m */
        private Double pointX;

        /** 超声波点状目标 y（POINT/KEYPOINT 类型），unit: m */
        private Double pointY;

        /** 超声波传感器到目标直线距离（回波距离语义），unit: m；仅点状/回波类有值 */
        private Double rangeM;

        /** 超声波目标高度类型（LOW|HIGH|TRAVERSIBLE|UNKNOWN，UssHeightType 枚举） */
        private String heightType;

        /** 目标概率（超声波 obj_probability），unit: percent，0-100 */
        private Double probability;

        /** valid|invalid|tentative（ObstacleStatus 枚举） */
        private String status;
    }
}
