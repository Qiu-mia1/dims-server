package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MXF 行驶轨迹点明细 (mxf_trajectory_point)
 *
 * <p>每个 MXF 包（= 一段行驶轨迹）解析出的 GPS 点。来源为包内日志各 SECTION
 * JSON 中的 {@code pg} / {@code enter_pg} / {@code exit_pg} / {@code cur_pg}
 * 字段（加偏经纬度，"经度,纬度"），经度在前。
 *
 * <p>{@code eventName} 非空表示该点属于某触发事件（选择事件可单独显示该事件的
 * 轨迹线）；为空表示包级轨迹点，按时间排序即该包的完整轨迹线。
 *
 * @feature: mxf
 */
@Data
@TableName("mxf_trajectory_point")
public class MxfTrajectoryPoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属 MXF 包 id（mxf_packet.id）
     */
    @TableField("packet_id")
    private String packetId;

    /**
     * 触发事件名（特殊事件 SECTION 归属，如 aeb / nop / take_over；包级点为 NULL）
     */
    @TableField("event_name")
    private String eventName;

    /**
     * 原始 SECTION 名
     */
    @TableField("section")
    private String section;

    /**
     * 点类型：pg | enter | exit | cur
     */
    @TableField("point_type")
    private String pointType;

    /**
     * 日志时间（UTC）
     */
    @TableField("log_ts")
    private LocalDateTime logTs;

    /**
     * 加偏经度（GCJ-02）
     */
    @TableField("lon")
    private Double lon;

    /**
     * 加偏纬度（GCJ-02）
     */
    @TableField("lat")
    private Double lat;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
