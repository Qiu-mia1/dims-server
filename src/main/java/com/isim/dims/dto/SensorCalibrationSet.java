package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 传感器标定集合（含坐标系/单位声明与车体尺寸）
 *
 * 对应 OpenAPI schema: SensorCalibrationSet。
 * required: [frame, unit, sensors, vehicle, has_calibration]
 * @feature: calibration
 */
@Data
public class SensorCalibrationSet {

    /** 坐标系：ego（原点=车体后轴中心，x 前 y 左 z 上，yaw=0 朝车头） */
    private String frame = "ego";

    /** 单位：m */
    private String unit = "m";

    /** 传感器标定记录列表 */
    private List<SensorCalibrationResponse> sensors;

    /** 车体尺寸（无标定时为默认值，如 4.8/1.9/2.8） */
    private VehicleSpec vehicle;

    /** false 表示该切片无标定：前端回退默认布局并标注「标定缺失」 */
    private boolean hasCalibration;
}
