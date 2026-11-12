package com.isim.dims.dto;

import com.isim.dims.entity.LogRecord;
import lombok.Data;

import java.util.List;

/**
 * 日志包列表响应（分页）
 *
 * <p>对应 GET /v1/log-records，按 log_time DESC 排序。</p>
 *
 * @feature: ingestion
 */
@Data
public class LogRecordListResponse {

    /** 日志包记录列表 */
    private List<LogRecord> items;

    /** 匹配条件的总记录数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private int page;

    /** 每页条数 */
    private int pageSize;
}
