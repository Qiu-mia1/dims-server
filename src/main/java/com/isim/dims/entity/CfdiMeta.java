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
 * CFDI 数据包元数据表 (cfdi_meta)
 *
 * CFDI 数据包专用，记录数据包全部元数据。
 * 与 data_slice 为一对一关系（一个切片对应一条 cfdi_meta 记录）。
 * @feature: cfdi
 */
@Data
@TableName("cfdi_meta")
public class CfdiMeta {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    @TableField("car_type")
    private String carType;

    @TableField("project")
    private String project;

    @TableField("event_name")
    private String eventName;

    @TableField("vin")
    private String vin;

    @TableField("collect_type")
    private String collectType;

    @TableField("data_source_env")
    private String dataSourceEnv;

    /** JSON: 元数据字典 */
    @TableField(value = "meta_data", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String metaData;

    /** JSON: 过滤元数据 */
    @TableField(value = "filter_meta_data", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String filterMetaData;

    /** JSON: 日志信息 */
    @TableField(value = "log_info_data", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String logInfoData;

    /** JSON: 上传完成标记 */
    @TableField(value = "upload_done_data", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String uploadDoneData;

    @TableField("good_flag")
    private String goodFlag;

    @TableField("dump_finish")
    private String dumpFinish;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
