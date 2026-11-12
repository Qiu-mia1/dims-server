package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日志包记录 (log_record)
 *
 * <p>一行对应 BOS 上一个以 {@code _log} 结尾的独立日志文件
 * （如 {@code {day}/{vin_md5}/{sessionDir}/public/<us>_forever_log_...}）。
 * 这类文件不参与切片导入，仅登记元数据供「日志浏览」页面展示与整体打包下载。</p>
 *
 * <p>元数据来源：同一会话目录下向上查找到的 {@code meta} 文件 + 对象 key 所在目录。</p>
 *
 * @feature: ingestion
 */
@Data
@TableName("log_record")
public class LogRecord {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 日志类型：forever_log / bsw_log，取自文件名中 {@code _log} 前的标识
     */
    @TableField("log_type")
    private String logType;

    /**
     * 目录内代表日志文件名（对象 key 最后一段，用于展示）
     */
    @TableField("file_name")
    private String fileName;

    /**
     * 目录内代表日志文件在 BOS 中的完整 key（字典序首个 {@code _log} 文件，仅用于溯源）
     */
    @TableField("bos_key")
    private String bosKey;

    /**
     * 日志文件所在目录（唯一：同一目录只登记一条记录），下载时按此前缀打包整个目录
     */
    @TableField("dir_prefix")
    private String dirPrefix;

    @TableField("bucket")
    private String bucket;

    /**
     * 目录内对象数，与打包下载条目数一致
     */
    @TableField("file_count")
    private Integer fileCount;

    /**
     * 目录内对象总字节数，与打包下载体积一致
     */
    @TableField("file_size")
    private Long fileSize;

    @TableField("last_modified")
    private LocalDateTime lastModified;

    /**
     * 文件名前缀微秒时间戳换算的 UTC 时间
     */
    @TableField("log_time")
    private LocalDateTime logTime;

    @TableField("car_type")
    private String carType;

    @TableField("sub_car_type")
    private String subCarType;

    @TableField("collect_type")
    private String collectType;

    @TableField("country")
    private String country;

    @TableField("data_source_env")
    private String dataSourceEnv;

    @TableField("dump_begin_us")
    private Long dumpBeginUs;

    @TableField("event_name")
    private String eventName;

    @TableField("fdi_type")
    private String fdiType;

    @TableField("is_soca")
    private Boolean isSoca;

    @TableField("maf_interface")
    private String mafInterface;

    @TableField("priority")
    private String priority;

    @TableField("project")
    private String project;

    @TableField("system_name")
    private String systemName;

    @TableField("use_anonymous_id")
    private Boolean useAnonymousId;

    @TableField("utc_diff_us")
    private Long utcDiffUs;

    @TableField("uuid")
    private String uuid;

    @TableField("vin")
    private String vin;

    /**
     * 来源 meta 文件 key
     */
    @TableField("meta_key")
    private String metaKey;

    /**
     * meta 文件原文（JSON 或 key=value）
     */
    @TableField("meta_json")
    private String metaJson;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
