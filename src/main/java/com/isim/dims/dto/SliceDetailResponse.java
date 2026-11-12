package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 切片详情响应（含 SensorStream 列表）
 *
 * 对应 OpenAPI schema: DataSliceDetail
 * 继承 DataSlice 所有字段，并附加 streams、camera_calibrations、cfdi_meta。
 * @feature: slice
 */
@Data
public class SliceDetailResponse {

    /** 切片 UUID */
    private String id;

    /** 切片名称 */
    private String name;

    /**
     * 原数据存储路径（data_slice.import_source_dir）：
     * 本地目录导入时记录的源会话目录绝对路径（服务器视角）；直接上传的切片为 NULL。
     */
    @JsonProperty("import_source_dir")
    private String importSourceDir;

    /** 原始 bag 文件字节数 */
    private Long fileSize;

    /** 数据时长，unit: 毫秒 */
    private Long durationMs;

    /** 数据起始时间，UTC RFC3339 */
    private String startTs;

    /** 数据结束时间，UTC RFC3339 */
    private String endTs;

    /** GPS 地理包围盒，WGS84 */
    private GpsBbox gpsBbox;

    /** 切片的传感器类型列表 */
    private List<String> sensorTypes;

    /** 总消息数 */
    private Long msgCountTotal;

    /** 标签名列表 */
    private List<String> tags;

    /** 切片状态：UPLOADING | CONVERTING | PARSING | TAGGING | READY | ERROR */
    private String status;

    /**
     * 按需加载状态（由 status + import_bos_key 推导）：
     * NOT_LOADED=仅 meta + 前视鱼眼（未加载全量数据，可播放鱼眼预览）|
     * LOADING=加载中 | LOADED=已加载完成（可回放/下载）
     */
    @JsonProperty("load_state")
    private String loadState;

    private String errorMsg;

    /** 数据来源类型：BAG | CFDI */
    @JsonProperty("source_type")
    private String sourceType;

    /** 导出入口路径 */
    private String downloadUrl;

    /** 切片创建时间，UTC RFC3339 */
    private String createdAt;

    /** 关联的导入任务 UUID，用于前端概览 Tab 关联任务状态时间线 */
    @JsonProperty("task_id")
    private String taskId;

    /** SensorStream 列表 */
    private List<StreamInfo> streams;

    /** 摄像头标定参数列表（仅 CFDI 时返回） */
    @JsonProperty("camera_calibrations")
    private List<CameraCalibrationItem> cameraCalibrations;

    /** CFDI 元数据摘要（仅 CFDI 时返回） */
    @JsonProperty("cfdi_meta")
    private CfdiMetaItem cfdiMeta;

    /** VIN 快照（data_slice 原生列，本地目录导入反查结果，未匹配为 NULL） */
    private String vin;

    /** 软件版本号（data_slice 原生列，本地目录导入解析 meta.system_name 写入；非 CFDI 来源为 NULL） */
    @JsonProperty("system_name")
    private String systemName;

    // ---- slice_stats 1:1 字段（可空 —— 无统计记录的切片可能不存在） ----

    /** 车辆型号（/config_service/vpi vehicle_type） */
    @JsonProperty("vehicle_type")
    private String vehicleType;

    /** 采集里程 = 结束里程 - 起始里程，unit: km */
    @JsonProperty("vehicle_mileage_delta")
    private Double vehicleMileageDelta;

    /** 平均车速，unit: m/s */
    @JsonProperty("speed_avg")
    private Double speedAvg;

    /** 最高车速，unit: m/s */
    @JsonProperty("speed_max")
    private Double speedMax;

    /** 主要道路场景 URBAN|HIGHWAY|TUNNEL|TOLL_GATE|ROUNDABOUT */
    @JsonProperty("road_scene")
    private String roadScene;

    /** 高速优先是否开启 */
    @JsonProperty("highway_mode")
    private Boolean highwayMode;

    /** 平均弯道曲率，unit: 1/m */
    @JsonProperty("curvature_avg")
    private Double curvatureAvg;

    /** 平均卫星数 */
    @JsonProperty("gnss_sat_num_avg")
    private Double gnssSatNumAvg;

    /** 平均 HDOP */
    @JsonProperty("gnss_hdop_avg")
    private Double gnssHdopAvg;

    /** 左变道次数 */
    @JsonProperty("lane_change_count_left")
    private Integer laneChangeCountLeft;

    /** 右变道次数 */
    @JsonProperty("lane_change_count_right")
    private Integer laneChangeCountRight;

    /** NOA 是否有激活段 */
    @JsonProperty("noa_active")
    private Boolean noaActive;

    /** AEB 是否有触发 */
    @JsonProperty("aeb_active")
    private Boolean aebActive;

    /** APA 是否有任务 */
    @JsonProperty("apa_active")
    private Boolean apaActive;

    /** ACC 是否有激活段 */
    @JsonProperty("acc_active")
    private Boolean accActive;

    /** LCC+ 是否有激活段 */
    @JsonProperty("lcc_active")
    private Boolean lccActive;

    /** NOA 漫游是否有激活段（debug.sm 模式码 3） */
    @JsonProperty("roaming_active")
    private Boolean roamingActive;

    /** AVP/RADS 是否有激活段（debug.sm 模式码 8/9） */
    @JsonProperty("avp_active")
    private Boolean avpActive;

    /** FCW 是否有触发 */
    @JsonProperty("fcw_active")
    private Boolean fcwActive;

    /** AES 是否有触发（/mff/aeb_sts_info aes_state == 3） */
    @JsonProperty("aes_active")
    private Boolean aesActive;

    /** ESS 是否有触发（/mff/aeb_sts_info ess_state == 3） */
    @JsonProperty("ess_active")
    private Boolean essActive;

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * 传感器流信息（Topic 级）
     *
     * 对应 OpenAPI schema: SensorStream
     */
    @Data
    public static class StreamInfo {

        /** 流 UUID */
        private String id;

        /** ROS Topic 完整路径，如 "/sensor/camera_front_wide/video" */
        private String topic;

        /** ROS 消息类型，如 "sensor_interface_msgs/CompressedVideo" */
        private String msgType;

        /** 传感器类型：camera | lidar | radar | gnss_imu | vehicle_signal | ultrasonic | adas | other */
        private String sensorCategory;

        /** 该 Topic 消息总数 */
        private Integer msgCount;

        /** 平均频率，unit: Hz */
        private Double frequencyHz;

        /** 首条消息时间戳，UTC RFC3339 */
        private String firstTs;

        /** 末条消息时间戳，UTC RFC3339 */
        private String lastTs;

        /** 传感器名（CFDI 专用），如 F30_front_far */
        @JsonProperty("sensor_name")
        private String sensorName;

        /** 传感器流时长（CFDI 专用），unit: 微秒 */
        @JsonProperty("duration_us")
        private Long durationUs;

        /** 扩展元数据（CFDI 专用），如 {"video_file": "F30_front_far.h265"} */
        private String metadata;
    }

    /**
     * 摄像头标定参数（CFDI 专用）
     *
     * 对应 OpenAPI schema: CameraCalibration
     */
    @Data
    public static class CameraCalibrationItem {

        private String id;

        @JsonProperty("sensor_name")
        private String sensorName;

        @JsonProperty("serial_number")
        private String serialNumber;

        @JsonProperty("camera_model")
        private String cameraModel;

        private Double fx;
        private Double fy;
        private Double cx;
        private Double cy;
        private Double kc2;
        private Double kc3;
        private Double kc4;
        private Double kc5;

        @JsonProperty("r_s2b")
        private List<Double> rS2b;

        @JsonProperty("t_s2b")
        private List<Double> tS2b;

        @JsonProperty("is_fisheye")
        private Boolean isFisheye;

        private Integer width;
        private Integer height;

        @JsonProperty("calib_version")
        private String calibVersion;

        @JsonProperty("calib_date")
        private String calibDate;
    }

    /**
     * CFDI 元数据摘要
     *
     * 对应 OpenAPI schema: CfdiMeta
     */
    @Data
    public static class CfdiMetaItem {

        @JsonProperty("car_type")
        private String carType;

        @JsonProperty("event_name")
        private String eventName;

        private String project;

        private String vin;

        @JsonProperty("good_flag")
        private String goodFlag;

        @JsonProperty("dump_finish")
        private String dumpFinish;

        /** JSON 对象: 完整元数据字典 */
        @JsonProperty("meta_data")
        private Object metaData;

        /** JSON 对象: 过滤元数据 */
        @JsonProperty("filter_meta_data")
        private Object filterMetaData;

        /** JSON 对象: 日志信息 */
        @JsonProperty("log_info_data")
        private Object logInfoData;

        /** JSON 对象: 上传完成标记 */
        @JsonProperty("upload_done_data")
        private Object uploadDoneData;

        @JsonProperty("sub_span_us")
        private Map<String, Long> subSpanUs;

        @JsonProperty("trigger_timestamp_us")
        private String triggerTimestampUs;

        @JsonProperty("collect_type")
        private String collectType;

        @JsonProperty("data_source_env")
        private String dataSourceEnv;
    }
}
