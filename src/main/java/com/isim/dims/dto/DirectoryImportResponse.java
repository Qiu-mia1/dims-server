package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 本地目录导入响应（202）
 *
 * 对应 POST /v1/data-slices/directory-import 的响应体。
 * OpenAPI schema: DirectoryImportResponse
 * 返回任务摘要：本次扫描结果、已创建导入任务的会话（imported）与被跳过会话（skipped）。
 * @feature: ingestion
 */
@Data
public class DirectoryImportResponse {

    /** 导入任务 ID（字符串 UUID），后续通过 GET /v1/tasks/{taskId} 查询导入进度 */
    private String jobId;

    /** 实际扫描的目录路径（请求原样返回） */
    private String requestedDir;

    /** 扫描到的有效 MD5 子目录数量 */
    private Integer scanned;

    /** 匹配到的会话总数（含 imported + skipped） */
    private Integer totalSessions;

    /** 已创建导入任务的会话列表 */
    private List<ImportedItem> imported;

    /** 被跳过的会话列表（含原因） */
    private List<SkippedItem> skipped;

    /**
     * 已创建导入任务的会话项
     */
    @Data
    public static class ImportedItem {

        /** 切片 ID（字符串 UUID） */
        private String sliceId;

        /** 导入任务 ID（字符串 UUID），查询进度用 */
        private String taskId;

        /** 源数据 MD5（子目录名），跨系统去重键 */
        private String md5;

        /** 会话 ID（来自原始数据，如 4bfb23eb_20260709143844460937_public） */
        private String sessionId;

        /** VIN 车架号 */
        private String vin;

        /** 车辆编号，如 V20 */
        private String vehicleNo;

        /** 初始状态，固定 UPLOADING（SliceStatus 枚举） */
        private String status;
    }

    /**
     * 被跳过的会话项
     */
    @Data
    public static class SkippedItem {

        /** 源数据 MD5（子目录名） */
        private String md5;

        /** 会话 ID（可解析时返回，无法解析可省略） */
        private String sessionId;

        /** 跳过原因，如 MD5_NOT_MAPPED（无对应会话映射记录） */
        private String reason;
    }
}
