package com.isim.dims.dto;

import lombok.Data;

/**
 * 传感器标定摄入请求（按 slice_id+sensor_name upsert）
 *
 * 对应 OpenAPI schema: SensorCalibrationRequest。
 * required: [sensor_name, sensor_type, x, y, z, yaw, pitch, roll]
 * @feature: calibration
 */
@Data
public class SensorCalibrationRequest {

    /** 传感器标识：相机用位置名（front_wide 等），雷达建议用 topic 段名（如 result_front） */
    private String sensorName;

    /** camera|radar|ultrasonic|imu|lidar（SensorCalibrationType 枚举） */
    private String sensorType;

    /** 安装位置 x（ego 系），unit: m */
    private Double x;

    /** 安装位置 y（ego 系），unit: m */
    private Double y;

    /** 安装位置 z（ego 系），unit: m */
    private Double z;

    /** 安装航向角，unit: rad */
    private Double yaw;

    /** 安装俯仰角，unit: rad */
    private Double pitch;

    /** 安装横滚角，unit: rad */
    private Double roll;

    /** 位姿所在坐标系，默认 ego */
    private String frame;

    /** 单位，默认 m */
    private String unit;

    /** 来源标识（manual / 文件名 / 标定任务名），仅记录 */
    private String source;
}
