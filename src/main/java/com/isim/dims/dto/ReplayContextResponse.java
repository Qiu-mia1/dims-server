package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 2D 回放页初始化上下文
 *
 * 对应 OpenAPI schema: ReplayContextResponse（契约 v1.3.0）。
 * required: [slice_id, start_ts, end_ts, video_streams, topics, calibration, vehicle, tags, data_ready]
 * @feature: replay
 */
@Data
public class ReplayContextResponse {

    /** 切片 UUID */
    private String sliceId;

    /** 切片数据起始时间（bag 时间戳，epoch），UTC RFC3339 */
    private String startTs;

    /** 切片数据结束时间（bag 时间戳，epoch），UTC RFC3339 */
    private String endTs;

    /** 数据时长，unit: ms */
    private Long durationMs;

    /** 视频流清单（数量不恒定，实测 10/11 路，前端按实际渲染） */
    private List<ReplayVideoStream> videoStreams;

    /** 切片全部 topic 清单（含传感器状态条的频率/消息数来源） */
    private List<ReplayTopicInfo> topics;

    /** 传感器标定集合 */
    private SensorCalibrationSet calibration;

    /** 车体尺寸 */
    private VehicleSpec vehicle;

    /** 场景标签名列表 */
    private List<String> tags;

    /** 是否已存在障碍/消息提取缓存 */
    private boolean dataReady;

    /**
     * 回放视频流（相机）
     *
     * 对应 OpenAPI schema: ReplayVideoStream。
     * required: [id, name, label, topic, url, mp4_available, default]
     */
    @Data
    public static class ReplayVideoStream {

        /** 相机位置标识（CameraPosition 枚举，禁用 F30/F100 别名） */
        private String id;

        /** 相机显示名，如 "前视广角" */
        private String name;

        /** 短标签（宫格内显示），与 name 一致或更短 */
        private String label;

        /** 完整 ROS Topic，如 "/sensor/camera_front_wide/video" */
        private String topic;

        /**
         * 可播放视频地址（HTTP Range 支持）；该路无 mp4 产物时返回空字符串，
         * mp4_available=false，前端必须降级（禁止直接播放 h265）
         */
        private String url;

        /** 是否存在 mp4 转码产物（false 时 url 为空串，前端降级） */
        @JsonProperty("mp4_available")
        private boolean mp4Available;

        /** 是否属于默认 6 路组合（front_wide/front_far/left_front/right_front/left_rear/right_rear） */
        @JsonProperty("default")
        private boolean isDefault;
    }

    /**
     * 回放 topic 信息（传感器状态条数据来源）
     *
     * 对应 OpenAPI schema: ReplayTopicInfo。
     * required: [name, type, sensor_category, msg_count, rate_hz, has_payload]
     */
    @Data
    public static class ReplayTopicInfo {

        /** ROS Topic 完整路径 */
        private String name;

        /** ROS 消息类型，如 "perception_interface_msgs/msg/RadarPerceptionResult" */
        private String type;

        /** 传感器类别（SensorCategory 枚举） */
        private String sensorCategory;

        /** 该 topic 消息总数 */
        private Integer msgCount;

        /** 平均频率（Hz） */
        private Double rateHz;

        /**
         * 是否含业务负载。v1.4 起一律 true：/momenta_public/*、/navigation/*
         * 实测（消息级检查）frame_id 内嵌完整 JSON 业务负载，不再按前缀排除
         */
        @JsonProperty("has_payload")
        private boolean hasPayload;

        /**
         * 数据分类（ReplayTopicUtil.dataCategory 枚举）：
         * sensor|perception|map|navigation|hdmap|planning|prediction|telemetry|
         * vehicle|worldmodel|location|routing|other，前端据此区分感知/地图叠加层
         */
        @JsonProperty("data_category")
        private String dataCategory;

        /** 首条消息时间（bag 时间戳），UTC RFC3339 */
        private String firstTs;

        /** 末条消息时间（bag 时间戳），UTC RFC3339 */
        private String lastTs;
    }
}
