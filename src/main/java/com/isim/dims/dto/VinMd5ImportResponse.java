package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 车辆信息（VIN-MD5 映射）Excel 导入响应
 * <p>
 * 对应 POST /v1/vin-md5-mappings/import 的响应体。
 * 逐行解析 Excel 的 vin / md5 两列（列名忽略大小写），按 vin 去重后批量 upsert；
 * 单条失败不影响其余行，失败明细逐行返回，便于用户改完 Excel 重传。
 *
 * @feature: ingestion
 */
@Data
public class VinMd5ImportResponse {

    /**
     * Excel 中解析到的有效数据行数（不含全空行）
     */
    private Integer totalRows;

    /**
     * 成功写入/更新的 VIN 数量
     */
    private Integer importedCount;

    /**
     * 失败行数
     */
    private Integer failedCount;

    /**
     * 失败明细
     */
    private List<FailedItem> failed;

    /**
     * 失败明细项
     */
    @Data
    public static class FailedItem {

        /**
         * Excel 行号（1-based，含表头行；无法定位时为 null）
         */
        private Integer row;

        /**
         * 车辆识别码 VIN
         */
        private String vin;

        /**
         * bag 文件 MD5
         */
        private String md5;

        /**
         * 失败原因，形如 {@code INVALID_MD5: md5 必须为 32 位十六进制字符串}
         */
        private String reason;
    }
}
