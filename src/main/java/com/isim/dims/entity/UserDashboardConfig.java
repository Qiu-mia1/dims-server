package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

/**
 * 用户个性化配置方案表 (user_dashboard_config)
 *
 * <p>数据回放看板个性化配置：不同角色（泊车 / 主动安全等）关注的数据项不同，
 * 由前端组装配置 JSON，后端<b>只存不读</b>（不解析、不校验内容语义）。
 * <b>一个用户可有多套方案</b>（{@code uk_user_dashboard_config_user_name} 复合唯一约束），
 * 方案名在单用户内唯一。</p>
 *
 * <p>{@code user_id} 取自 IAMS JWT 的 {@code sub} claim
 * （docs/子系统对接指南.md §3.1）。DIMS 本地无用户表，故不设外键。</p>
 *
 * @feature: replay
 */
@Data
@TableName("user_dashboard_config")
public class UserDashboardConfig {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** IAMS 用户 UUID（JWT sub claim） */
    @TableField("user_id")
    private String userId;

    /** 方案名称，人类可读；单用户内唯一（VARCHAR(120) NOT NULL） */
    @TableField("name")
    private String name;

    /**
     * 方案描述，可选（NULL 表示无描述；VARCHAR(500)）。
     *
     * <p>⚠️ {@code updateStrategy = ALWAYS} 是必需的：MyBatis-Plus 默认忽略 null 字段，
     * 用默认策略时「编辑接口传 null 清空描述」会<b>静默失效</b>——
     * 不生成 {@code SET description = NULL}，描述仍保持原值。
     * 编辑接口为全量替换语义，故本列必须强制参与 UPDATE。</p>
     */
    @TableField(value = "description", updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    /**
     * 前端组装的配置 JSON（DB 为 JSONB 列），后端不解析内容。
     *
     * <p>复用 {@link SensorStream.JsonbTypeHandler}（{@code ps.setObject(i, str, Types.OTHER)}），
     * 与 sensor_calibration.pose_json / replay_extraction_task.topics / export_task.topics 同款；
     * 依赖数据源 URL 的 {@code stringtype=unspecified} 参数。</p>
     */
    @TableField(value = "config_json", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String configJson;

    /** 创建时间（UTC），INSERT 自动填充 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 最近更新时间（UTC）。
     *
     * <p>⚠️ {@code MyMetaObjectHandler.strictUpdateFill} 仅在字段为 null 时填充；
     * 「先查后改」路径的 entity 来自 DB（已非 null），需在 Service 层显式刷新。</p>
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
