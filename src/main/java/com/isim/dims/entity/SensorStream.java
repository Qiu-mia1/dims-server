package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 传感器流索引表 (sensor_stream)
 *
 * 对应数据库中传感器流索引表，每个 Topic 一条记录，不解析消息内容。
 * metadata 在 DB 中为 JSON 对象字符串（分辨率、编码格式等），Entity 层声明为 String。
 * @feature: visualization
 */
@Data
@TableName("sensor_stream")
public class SensorStream {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    @TableField("topic")
    private String topic;

    @TableField("msg_type")
    private String msgType;

    @TableField("sensor_category")
    private String sensorCategory;

    /** 传感器名（VARCHAR(100)），冗余列用于精确匹配，避免 JSON LIKE 全表扫描 */
    @TableField("sensor_name")
    private String sensorName;

    @TableField("msg_count")
    private Integer msgCount;

    @TableField("frequency_hz")
    private Double frequencyHz;

    @TableField("first_ts")
    private LocalDateTime firstTs;

    @TableField("last_ts")
    private LocalDateTime lastTs;

    /**
     * JSON 对象字符串（分辨率、编码格式等 Topic 特定元数据）
     */
    @TableField(value = "metadata", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbTypeHandler.class)
    private String metadata;

    /** CFDI 传感器流时长（微秒），由 sub_span_us 分发 */
    @TableField("duration_us")
    private Long durationUs;

    /**
     * PostgreSQL JSONB handler for the entity's JSON text representation.
     */
    public static class JsonbTypeHandler extends BaseTypeHandler<String> {

        @Override
        public void setNonNullParameter(
                PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
                throws SQLException {
            ps.setObject(i, parameter, Types.OTHER);
        }

        @Override
        public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
            return rs.getString(columnName);
        }

        @Override
        public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
            return rs.getString(columnIndex);
        }

        @Override
        public String getNullableResult(CallableStatement cs, int columnIndex)
                throws SQLException {
            return cs.getString(columnIndex);
        }
    }

    /**
     * PostgreSQL JSONB handler for object/list entity fields.
     * JacksonTypeHandler otherwise binds JSON as VARCHAR.
     */
    public static class JacksonJsonbTypeHandler extends JacksonTypeHandler {
        public JacksonJsonbTypeHandler(Class<?> type) {
            super(type);
        }

        public JacksonJsonbTypeHandler(Class<?> type, java.lang.reflect.Field field) {
            super(type, field);
        }

        @Override
        public void setNonNullParameter(
                PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
                throws SQLException {
            ps.setObject(i, toJson(parameter), Types.OTHER);
        }
    }
}
