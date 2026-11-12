package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MXF 积压车辆忽略名单（mxf_offline_note）。
 *
 * <p>单车 MXF 看板「数据异常车辆」列表支持为某辆车填写备注；填过备注的车辆不再计入
 * 异常统计与积压告警阈值判断（前端可查看与撤销）。按 {@code vin} 唯一 —— 一个车辆标识
 * 只保留一条备注，重复提交即覆盖。</p>
 *
 * <p>{@code vin} 存的是 {@code mxf_packet.vin} 原值（32 位 md5），不是展示用的真实 VIN。</p>
 *
 * @feature: mxf
 */
@Data
@TableName("mxf_offline_note")
public class MxfOfflineNote {

    /** 主键（UUID） */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 车辆标识（mxf_packet.vin 原值，唯一） */
    @TableField("vin")
    private String vin;

    /** 用户填写的备注 */
    @TableField("note")
    private String note;

    /** 填写人（JWT subject，取不到时为 null） */
    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
