package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * VIN 与 MD5 对应关系表 (vin_md5_mapping)
 *
 * 22 台测试车的 VIN 与 bag 文件 MD5 对应关系（源：vin对应md5.xlsx Sheet2，
 * Flyway V16 种子数据入库）。数据接入/驾驶舱告警场景按 MD5 或 VIN 反查
 * 车辆型号/配置/用途等元数据（cfdi_meta 等表仅存 VIN，可经本表关联补齐）。
 * vin 与 md5 均有唯一索引（md5 唯一支持按文件去重）。
 * 空值按源数据存空字符串 ''（optionalEquipment 等可选列）。
 * @feature: ingestion
 */
@Data
@TableName("vin_md5_mapping")
public class VinMd5Mapping {

    /** 主键自增 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 车辆编号（V15 等，Excel 内编号） */
    @TableField("vehicle_no")
    private String vehicleNo;

    /** 车型代码（835BVP4-15 等） */
    @TableField("model_code")
    private String modelCode;

    /** 车辆识别码 VIN（17 位，唯一） */
    @TableField("vin")
    private String vin;

    /** bag 文件 MD5（32 位 hex，唯一） */
    @TableField("md5")
    private String md5;

    /** 动力类型 BEV|REEV */
    @TableField("vehicle_type")
    private String vehicleType;

    /** 配置包 OuterBanks|Badlands */
    @TableField("configuration")
    private String configuration;

    /** 选装配置（空簧/L2.9 等，多值可含逗号） */
    @TableField("optional_equipment")
    private String optionalEquipment;

    /** 车辆描述 */
    @TableField("vehicle_desc")
    private String vehicleDesc;

    /** 部门 PMT5|PAT|VI */
    @TableField("department")
    private String department;

    /** 车辆用途说明 */
    @TableField("purpose")
    private String purpose;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
