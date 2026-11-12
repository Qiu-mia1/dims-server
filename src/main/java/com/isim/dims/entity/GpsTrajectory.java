package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GPS 轨迹点表 (gps_trajectory)
 *
 * 对应数据库中 GPS 轨迹缓存表。
 * 每个切片通常包含数千个轨迹点（<5000 点）。
 * 建立 idx_slice_id 索引支持按切片快速查询。
 * @feature: trajectory
 */
@Data
@TableName("gps_trajectory")
public class GpsTrajectory {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联切片 ID */
    @TableField("slice_id")
    private String sliceId;

    /** 时间戳，UTC RFC3339 */
    @TableField("ts")
    private LocalDateTime ts;

    /** 纬度，WGS84，unit: degrees */
    @TableField("lat")
    private Double lat;

    /** 经度，WGS84，unit: degrees */
    @TableField("lon")
    private Double lon;

    /** 海拔，unit: m */
    @TableField("altitude")
    private Double altitude;

    /** 航向角，unit: degrees */
    @TableField("heading")
    private Double heading;

    /** 速度，unit: m/s */
    @TableField("speed")
    private Double speed;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
