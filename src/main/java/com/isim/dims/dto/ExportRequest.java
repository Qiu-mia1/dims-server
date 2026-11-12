package com.isim.dims.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 导出请求参数
 *
 * 对应 POST /v1/data-slices/{sliceId}/exports 的请求体。
 * OpenAPI schema: ExportRequest
 * @feature: export
 */
@Data
public class ExportRequest {

    /** 导出时间窗口起点，UTC RFC3339 */
    @NotNull(message = "start_ts is required")
    private String startTs;

    /** 导出时间窗口终点，UTC RFC3339 */
    @NotNull(message = "end_ts is required")
    private String endTs;

    /** 要导出的 Topic 列表，为空则导出全部 */
    private List<String> topics;

    /** 导出格式，默认 "bag" */
    private String format = "bag";
}
