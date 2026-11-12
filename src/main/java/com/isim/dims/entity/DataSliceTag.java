package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切片-标签关联表 (data_slice_tag)
 *
 * 对应数据库中切片与标签的多对多关联表。
 * 使用单主键 id (UUID) 而非复合主键，简化 MyBatis-Plus 操作。
 * source 标识标签来源：auto（自动打标）或 manual（手动关联）。
 *
 * <p>⚠️ {@code tag_id} 指向的是 **SIMS 片段域标签 UUID**，本地不再有 tag 表
 * （V29 移除），因此没有外键约束；标签名由 {@code SimsTagCatalog} 解析。
 * @feature: tag
 */
@Data
@TableName("data_slice_tag")
public class DataSliceTag {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    @TableField("tag_id")
    private String tagId;

    /**
     * auto|manual
     */
    @TableField("source")
    private String source;

    /**
     * 打标关联建立时间（UTC）。
     *
     * <p>V29 随本地 {@code tag} 表移除而补上：此前 {@code SliceTagResponse.timestamp}
     * 取的是 {@code tag.created_at}（标签**定义**的创建时间），语义本就是错的。
     * 补列后 timestamp 语义修正为「打标时刻」。
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
