package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 最近上传列表响应（分页）
 *
 * 对应 GET /v1/dashboard/recent-uploads 的响应。
 * OpenAPI schema: RecentUploadsResponse
 * @feature: dashboard
 */
@Data
public class RecentUploadsResponse {

    /** 最近上传条目列表 */
    private List<RecentUploadItem> items;

    /** 符合条件的总数 */
    private Long total;

    /** 当前页码 */
    private Integer page;

    /** 每页条数 */
    private Integer pageSize;

    /**
     * 最近上传条目
     */
    @Data
    public static class RecentUploadItem {

        /** 切片 UUID */
        private String id;

        /** 切片名称 */
        private String name;

        /** 原始 bag 文件字节数 */
        private Long fileSize;

        /** 人类可读的文件大小，如 "202.67 MB" */
        private String fileSizeHuman;

        /** 数据时长（毫秒） */
        private Long durationMs;

        /** 传感器类型列表 */
        private List<String> sensorTypes;

        /** 切片状态 */
        private String status;

        /** 上传时间，UTC RFC3339 */
        private String createdAt;
    }
}
