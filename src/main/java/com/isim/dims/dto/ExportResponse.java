package com.isim.dims.dto;

import lombok.Data;

/**
 * 导出任务详情响应
 *
 * 对应 GET /v1/exports/{exportId} 的响应。
 * OpenAPI schema: ExportTask
 * @feature: export
 */
@Data
public class ExportResponse {

    /** 导出任务 UUID */
    private String exportId;

    /** 关联的切片 UUID */
    private String sliceId;

    /** 任务状态：PENDING | PROCESSING | READY | ERROR | EXPIRED */
    private String status;

    /** 导出格式：bag | mp4 | images | json */
    private String format;

    /** 导出文件字节数 */
    private Long fileSize;

    /** 导出文件下载链接（临时有效） */
    private String downloadUrl;

    /** 下载链接过期时间，UTC RFC3339 */
    private String expiresAt;

    /** 导出任务创建时间，UTC RFC3339 */
    private String createdAt;
}
