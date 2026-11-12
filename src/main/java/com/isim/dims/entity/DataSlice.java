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
 * 数据切片主表 (data_slice)
 *
 * 对应数据库中数据切片主表，记录每个上传的 ROS1 bag 文件元数据信息。
 * GPS 包围盒在 DB 中存储为 4 个独立 Double 字段，API 响应层组装为嵌套对象。
 * sensor_types 在 DB 中为 JSON 数组字符串，Entity 层声明为 String，Service 层做序列化/反序列化。
 *
 * <p><b>新手注意：本表字段由多个手工迁移脚本拼装而成</b>（项目无 Flyway，脚本需 DBA 按序执行），
 * 新增字段前请先对照 resources/db/migration/ 与 docs/DIMS-数据库表结构与ER图.md：</p>
 * <ul>
 *   <li>V1 基线：id/name/file_path/起止时间/GPS 包围盒/sensor_types/status 等</li>
 *   <li>V3 source_type（V19 改语义为 MMT|ZYT|Hirain）、V6 trajectory_status/trajectory_checked_at</li>
 *   <li>V17 vin_md5_id/vin/import_md5/session_id/import_source_dir（本地目录导入 + 唯一约束）</li>
 *   <li>V18 utc_diff_us、V19 storage_type(LOCAL|MINIO)、V24 trigger_time</li>
 *   <li>V29 import_bos_key（meta-only 按需加载）、V30 system_name</li>
 * </ul>
 * <p>status 除 UPLOADING/READY/ERROR 外，还包括 meta-only 流程的 META_READY、LOADING（见 V29 脚本）。
 * storage_type=MINIO 时数据本体在对象存储，归档后读取返回 410 DATA_ARCHIVED。</p>
 *
 * @feature: slice
 */
@Data
@TableName("data_slice")
public class DataSlice {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("name")
    private String name;

    //触发类型
    @TableField("event_name")
    private String eventName;

    @TableField("file_path")
    private String filePath;

    /**
     * 存储模式（V19 迁移新增列）：LOCAL=本地文件系统 | MINIO=MinIO 对象存储
     * 存量记录默认 LOCAL（数据归档：minio 模式下数据内容读取返回 410 DATA_ARCHIVED）
     */
    @TableField("storage_type")
    private String storageType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("start_ts")
    private LocalDateTime startTs;

    @TableField("end_ts")
    private LocalDateTime endTs;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("gps_min_lat")
    private Double gpsMinLat;

    @TableField("gps_min_lon")
    private Double gpsMinLon;

    @TableField("gps_max_lat")
    private Double gpsMaxLat;

    @TableField("gps_max_lon")
    private Double gpsMaxLon;

    /**
     * JSON 数组字符串，如 ["camera","lidar","radar"]
     */
    @TableField(value = "sensor_types", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String sensorTypes;

    @TableField("msg_count_total")
    private Long msgCountTotal;

    /**
     * UPLOADING|CONVERTING|PARSING|TAGGING|READY|ERROR/WORN
     */
    @TableField("source_type")
    private String sourceType;

    /**
     * 状态：UPLOADING|CONVERTING|PARSING|TAGGING|VIDEO_EXTRACTING|STATS_EXTRACTING|SCENE_EXTRACTING
     *      |READY|ERROR|WORN
     *      |META_READY（仅元数据就绪，MMT meta-only 导入，可播放鱼眼预览）
     *      |LOADING（用户触发「加载」，BOS 全量拉取中）
     */
    @TableField("status")
    private String status;

    @TableField("error_msg")
    private String errorMsg;

    /**
     * GPS 轨迹提取结果标记（V6 迁移新增列）
     * NULL=未提取过 | EXTRACTED=已提取（gps_trajectory 有缓存点）| EMPTY=已确认无 GPS 数据
     * EMPTY 标记用于防止：sensor_stream 判定有 GPS 能力但 Python 提取结果为 0 点时，
     * GET trajectory 每次请求都重复触发异步提取（死循环）
     */
    @TableField("trajectory_status")
    private String trajectoryStatus;

    /** 最近一次轨迹提取完成时间（UTC），与 trajectory_status 配套 */
    @TableField("trajectory_checked_at")
    private LocalDateTime trajectoryCheckedAt;

    /**
     * 车辆逻辑外键 → vin_md5_mapping.id（V17 新增列，本地目录导入反查 VIN 时记录）
     */
    @TableField("vin_md5_id")
    private Long vinMd5Id;

    /** VIN 快照（本地目录导入反查结果，未匹配为 NULL） */
    @TableField("vin")
    private String vin;

    /** 本地导入源 MD5 子目录名（32 位 hex，如 ff74ef8686a4c591241480264bfb23eb） */
    @TableField("import_md5")
    private String importMd5;

    /** 本地导入会话目录名（如 4bfb23eb_20260709143844460937_public） */
    @TableField("session_id")
    private String sessionId;

    /** 本地导入源会话目录绝对路径（只读源，流水线复制其 public/ 到工作区） */
    @TableField("import_source_dir")
    private String importSourceDir;

    /**
     * BOS 会话对象前缀（V29 迁移新增列，MMT meta-only 导入写入），
     * 如 {bosPrefix}/{yyyyMMdd}/{md5}/{sessionDir}。
     * 用户在数据浏览器点「加载」时，按此前缀从 BOS 全量拉取文件并走完整导入。
     */
    @TableField("import_bos_key")
    private String importBosKey;

    /**
     * 软件版本号（V30 迁移新增列）
     * 来源：本地目录导入解析 meta 时，meta 文件根级字段 system_name。
     * 非 CFDI 来源 / meta 缺失 / 字段非法时为 NULL。
     * 前端切片详情页以本字段展示「软件版本」（替换原 utc_diff_us 字段）。
     */
    @TableField("system_name")
    private String systemName;

    /**
     * CFDI meta 时钟偏移（V18 迁移新增列，单位微秒）
     * bag 时间戳 + utc_diff_us × 1000 = 真实 UTC（us → ns）。
     * 非 CFDI 来源（BAG/LOCAL）meta 缺失时为 NULL。
     */
    @TableField("utc_diff_us")
    private Long utcDiffUs;

    /**
     * 触发时间（V24 迁移新增列，UTC）
     * 来源：本地目录导入解析 meta 时，trigger_timestamp_us（数据面本地时钟微秒）
     * + utc_diff_us（本地时钟 → UTC 偏移微秒）= 事件真实 UTC 触发时刻。
     * 非 CFDI 来源 / meta 缺失时为 NULL；前端按东八区（北京时间）展示。
     */
    @TableField("trigger_time")
    private LocalDateTime triggerTime;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // -----------------------------------------------------------------------
    // 以下为查询冗余列（非 data_slice 表列），由 searchSlices 的
    // LEFT JOIN slice_stats 注入，用于仪表盘数据包列表展示。
    // -----------------------------------------------------------------------

    /** 车辆型号（slice_stats.vehicle_type，LEFT JOIN 冗余列；无统计记录时为 null） */
    @TableField(exist = false)
    private String vehicleType;

    /** slice_stats 关联行 slice_id（LEFT JOIN 冗余列，用于判断是否存在统计记录） */
    @TableField(exist = false)
    private String statsSliceId;

    /** slice_stats 状态（LEFT JOIN 冗余列；SUCCESS=统计可用，FAILED=提取失败，无记录时为 null） */
    @TableField(exist = false)
    private String statsStatus;

    /** NOA 是否有激活段（slice_stats.noa_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean noaActive;

    /** AEB 是否有触发（slice_stats.aeb_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean aebActive;

    /** APA 是否有任务（slice_stats.apa_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean apaActive;

    /** ACC 是否有激活段（slice_stats.acc_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean accActive;

    /** LCC+ 是否有激活段（slice_stats.lcc_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean lccActive;

    /** NOA 漫游是否有激活段（slice_stats.roaming_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean roamingActive;

    /** AVP/RADS 是否有激活段（slice_stats.avp_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean avpActive;

    /** FCW 是否有触发（slice_stats.fcw_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean fcwActive;

    /** AES 是否有触发（slice_stats.aes_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean aesActive;

    /** ESS 是否有触发（slice_stats.ess_active LEFT JOIN） */
    @TableField(exist = false)
    private Boolean essActive;
}
