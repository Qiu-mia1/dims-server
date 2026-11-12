package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 MXF 数据包导入结果项。
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfImportResultItem {

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 压缩包 MD5
     */
    private String fileMd5;

    /**
     * 压缩包字节数
     */
    private Long fileSize;

    /**
     * 是否成功入库
     */
    private Boolean success;

    /**
     * 已存在（基于 MD5 唯一约束），true=跳过
     */
    private Boolean skipped;

    /**
     * 入库后的 packetId（成功或失败时的最新记录）
     */
    private String packetId;

    /**
     * 解析状态 PARSED | FAILED | DUPLICATE
     */
    private String status;

    /**
     * 解析出的日志行数
     */
    private Integer recordCount;

    /**
     * 独立 SECTION 数
     */
    private Integer sectionCount;

    /**
     * 解析过程中遇到的错误信息（成功时为 null）
     */
    private String errorMsg;
}
