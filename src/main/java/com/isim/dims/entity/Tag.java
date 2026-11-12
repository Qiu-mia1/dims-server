package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标签库表 (tag)
 *
 * 对应数据库中标签定义表，存储可复用的标签元数据。
 * rule_expr 为自动打标规则表达式（Phase 2+ DSL 支持）。
 * @feature: tag
 */
@Data
@TableName("tag")
public class Tag {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("name")
    private String name;

    @TableField("category")
    private String category;

    /** SIMS 分类节点 ID。 */
    @TableField("category_id")
    private String categoryId;

    /** SIMS 分类完整路径。 */
    @TableField("category_path")
    private String categoryPath;

    /** 标签来源，例如 sims。 */
    @TableField("source")
    private String source;

    /**
     * 标签作用域（SIMS 契约 TagScope：SCENARIO / ASSET / FRAGMENT）。
     *
     * <p>契约 v3.2.0 起三域完全隔离（每域一棵独立分类树、标签不跨域共享），
     * DIMS 只消费片段域，故本地行恒为 {@code FRAGMENT}。
     * V20 一次性导入的 301 行为切换前的场景域目录，scope 为 NULL —— 见 V24 迁移说明。
     */
    @TableField("scope")
    private String scope;

    @TableField("description")
    private String description;

    @TableField("rule_expr")
    private String ruleExpr;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
