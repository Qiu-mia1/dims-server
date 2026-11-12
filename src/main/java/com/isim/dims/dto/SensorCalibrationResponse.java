package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 传感器标定记录
 *
 * 对应 OpenAPI schema: SensorCalibrationResponse。
 * required: [id, slice_id, sensor_name, sensor_type, x, y, z, yaw, pitch, roll, frame, unit]
 * @feature: calibration
 */
@Data
public class SensorCalibrationResponse {

    /** 记录 UUID */
    private String id;

    /** 切片 UUID */
    private String sliceId;

    /** 传感器标识（相机位置名 / 雷达 topic 段名） */
    private String sensorName;

    /** camera|radar|ultrasonic|imu|lidar */
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

    /** 坐标系：ego */
    private String frame;

    /** 长度单位：m */
    private String unit;

    /** 来源标识（yaml|manual），仅记录 */
    private String source;

    /** 摄入时间，UTC RFC3339 */
    private String createdAt;
}
