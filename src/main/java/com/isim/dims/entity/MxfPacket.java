package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.isim.dims.entity.SensorStream.JsonbTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

/**
 * MXF 数据包主表 (mxf_packet)
 *
 * <p>每个 MXF 埋点数据包压缩包（.tar.gz）对应一行记录，存储来自 meta 文件的
 * 车辆/事件元数据以及解析摘要（record_count / section_count / 时间区间）。
 *
 * <p>存储策略（V27 调整）：
 * <ul>
 *   <li>不进 data_slice，不走 CFDI 转换流水线</li>
 *   <li>日志文件（解压后 .data）整体存对象存储（MinIO），key 记录在 data_object_key</li>
 *   <li>导入时一次性解析出聚合快照（section 计数 / AEB / APA / AVP / 里程 / 时长），
 *       以 JSONB 形式落在 section_stats，看板直接汇总，不再逐行存明细</li>
 * </ul>
 *
 * @feature: mxf
 */
@Data
@TableName(value = "mxf_packet", autoResultMap = true)
public class MxfPacket {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 原始文件名（exp_data_packet_<VIN_md5后8>_<usTimestamp>.tar.gz）
     */
    @TableField("file_name")
    private String fileName;

    /**
     * 压缩包 MD5（32 位 hex），全局唯一
     */
    @TableField("file_md5")
    private String fileMd5;

    /**
     * 压缩包字节数
     */
    @TableField("file_size")
    private Long fileSize;

    /**
     * VIN 快照
     * <p>导入时用 meta.vin 反查 {@code vin_md5_mapping} 换成<b>明文 VIN</b> 落库；
     * 映射未命中时保留 meta 原值（可能是 32 位摘要）。</p>
     */
    @TableField("vin")
    private String vin;

    /**
     * 该 VIN 对应的 32 位 md5（来自 meta.vin_md5 或经 vin_md5_mapping 反查补全）
     */
    @TableField("vin_md5")
    private String vinMd5;

    /**
     * 事件类型（meta.event_name）
     */
    @TableField("event_name")
    private String eventName;

    /**
     * 车型
     */
    @TableField("car_type")
    private String carType;

    /**
     * 项目名称
     */
    @TableField("project")
    private String project;

    /**
     * 采集类型
     */
    @TableField("collect_type")
    private String collectType;

    /**
     * 触发时间戳（数据侧本地时钟，微秒）
     */
    @TableField("trigger_timestamp_us")
    private Long triggerTimestampUs;

    /**
     * 本地时钟与 UTC 的偏移（微秒，UTC = local - utc_diff_us）
     */
    @TableField("utc_diff_us")
    private Long utcDiffUs;

    /**
     * 转换后的 UTC 触发时间
     */
    @TableField("trigger_time")
    private LocalDateTime triggerTime;

    /**
     * 日志首行时间（UTC）
     */
    @TableField("packet_start")
    private LocalDateTime packetStart;

    /**
     * 日志末行时间（UTC）
     */
    @TableField("packet_end")
    private LocalDateTime packetEnd;

    /**
     * 解析出的日志行数
     */
    @TableField("record_count")
    private Integer recordCount;

    /**
     * 独立 SECTION 数
     */
    @TableField("section_count")
    private Integer sectionCount;

    /**
     * 属于 8 类特殊事件的记录数
     */
    @TableField("special_event_count")
    private Integer specialEventCount;

    /**
     * PARSED|FAILED
     */
    @TableField("status")
    private String status;

    /**
     * 解析失败原因（成功时 NULL）
     */
    @TableField("error_msg")
    private String errorMsg;

    /**
     * 调用方 source（MMT/ZYT/Hirain/手工导入 等）
     */
    @TableField("source")
    private String source;

    /**
     * 存量兼容字段：早期版本把日志文件（解压后 .data）写入 MinIO 后记录的 key。
     * <p>导入流程已不再保存原始日志，新数据恒为 null；
     * 历史值可通过 {@code POST /v1/mxf/raw-logs/purge} 清理。</p>
     */
    @TableField("data_object_key")
    private String dataObjectKey;

    /**
     * 日志文件解压后字节数（仅统计口径，不代表对象存储占用）
     */
    @TableField("data_size_bytes")
    private Long dataSizeBytes;

    /**
     * 聚合快照 JSON：{sections:{...}, aebCount, apaCount, avpCount, distanceKm, durationHours}
     */
    @TableField(value = "section_stats", jdbcType = JdbcType.OTHER, typeHandler = JsonbTypeHandler.class)
    private String sectionStats;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
