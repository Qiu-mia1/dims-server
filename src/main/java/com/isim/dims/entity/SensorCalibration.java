package com.isim.dims.entity;

import org.apache.ibatis.type.JdbcType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 传感器安装标定表 (sensor_calibration)
 *
 * 通用传感器安装位姿（相机/雷达/超声波/IMU/LiDAR），ego 系。
 * 按 (slice_id, sensor_name) 唯一约束 upsert。
 *
 * pose_json 为 JSON 对象字符串：
 *   {"x":..,"y":..,"z":..,"yaw":..,"pitch":..,"roll":..}
 *   x/y/z 单位 m；yaw/pitch/roll 单位 rad；frame=ego（原点=车体后轴中心，x 前 y 左 z 上）
 * @feature: calibration
 */
@Data
@TableName("sensor_calibration")
public class SensorCalibration {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    /** 相机用位置名（front_wide 等），雷达用 topic 段名（result_front 等） */
    @TableField("sensor_name")
    private String sensorName;

    /** camera|radar|ultrasonic|imu|lidar */
    @TableField("sensor_type")
    private String sensorType;

    /** 坐标系：ego */
    @TableField("frame")
    private String frame;

    /** 长度单位：m */
    @TableField("unit")
    private String unit;

    /** JSON: {x,y,z,yaw,pitch,roll} */
    @TableField(value = "pose_json", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String poseJson;

    /** yaml|manual，仅记录 */
    @TableField("source")
    private String source;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
