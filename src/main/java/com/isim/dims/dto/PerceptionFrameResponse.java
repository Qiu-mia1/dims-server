package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 融合感知目标帧（窗口内最近一帧聚合，smooth_object）
 *
 * <p>数据源：/momenta_public/perception/fusion/smooth_object（10Hz），消息以
 * std_msgs/msg/Header 录制，业务负载内嵌 frame_id JSON（perception_fusion_objects_data）。</p>
 *
 * <p>坐标系（契约 §7 标注，重要）：本数据为<strong>全局系</strong>——WGS84 投影
 * 局部网格（类 UTM），坐标值达数百米（如 x=-278, y=525），<strong>不是</strong>
 * ego 局部系，不得与雷达/超声波（ego 系）或 ddld 地图（ego 局部系）直接叠加，
 * 须先做坐标系变换。unit: m（位置/尺寸）、m/s（速度）、rad（航向）。</p>
 *
 * <p>时间：time/time_ns 为 bag 时间戳（epoch）；frame_id 内 header.stamp 为
 * 上电相对时间，仅调试参考，不用于同步。</p>
 * @feature: visualization
 */
@Data
public class PerceptionFrameResponse {

    /** 实际返回帧的消息时间（bag 时间戳），UTC RFC3339 */
    private String time;

    /** 实际返回帧的消息时间，bag 时间戳纳秒整数，unit: ns（与 time 同一时刻） */
    private Long timeNs;

    /** 坐标系：global = WGS84 投影局部网格（非 ego），原点为局部网格原点 */
    private String frame = "global";

    /** 长度单位：m（速度单位 m/s、航向 rad 见各字段） */
    private String unit = "m";

    /** 目标列表（单帧 ≤ 200） */
    private List<PerceptionObject> objects;

    /**
     * 融合感知目标（全局系，unit: m / m/s / rad）。
     *
     * 对应任务契约 PerceptionObject。
     */
    @Data
    public static class PerceptionObject {

        /** 融合跟踪 ID（跨帧稳定，跟踪链生命周期内唯一） */
        private Long trackId;

        /** 目标类别：general_object|vehicle|vru|traffic_barrier|traffic_light|unknown */
        @JsonProperty("class")
        private String clazz;

        /** 置信度 score，0-1 */
        private Double confidence;

        /** 目标长度，unit: m */
        private Double lengthM;

        /** 目标宽度，unit: m */
        private Double widthM;

        /** 目标高度，unit: m */
        private Double heightM;

        /** 目标位置 x（全局系），unit: m */
        private Double x;

        /** 目标位置 y（全局系），unit: m */
        private Double y;

        /** 目标位置 z（全局系），unit: m */
        private Double z;

        /** 速度 x（全局系），unit: m/s */
        private Double vx;

        /** 速度 y（全局系），unit: m/s */
        private Double vy;

        /** 航向角（全局系，x 轴正方向为 0，逆时针为正），unit: rad */
        private Double headingYaw;
    }
}
