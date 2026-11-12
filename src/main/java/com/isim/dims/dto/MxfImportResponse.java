package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MXF 数据包批量导入响应。
 *
 * <p>整体结构与 {@link DirectoryImportResponse} 类似，但每个 item 的字段是
 * MXF 专有的（packetId / recordCount / sectionCount 等）。
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfImportResponse {

    /**
     * 调用方 source（由 controller 直接回填）
     */
    private String source;

    /**
     * 总文件数
     */
    private Integer totalFiles;

    /**
     * 成功条数
     */
    private Integer successCount;

    /**
     * 跳过（重复）条数
     */
    private Integer skippedCount;

    /**
     * 失败条数
     */
    private Integer failedCount;

    /**
     * 总入库日志行数
     */
    private Long totalRecords;

    /**
     * 入库耗时（毫秒）
     */
    private Long costMs;

    /**
     * 起始时间
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间
     */
    private LocalDateTime finishedAt;

    /**
     * 每个文件的导入结果
     */
    private List<MxfImportResultItem> items;
}
