package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 信号提取任务结果响应
 *
 * 对应 GET /v1/signal-extractions/{taskId} 的响应（status=READY 时）。
 * OpenAPI schema: SignalExtractionResult
 * @feature: visualization
 */
@Data
public class SignalDataResponse {

    /** 任务 UUID */
    private String taskId;

    /** 关联切片 UUID */
    private String sliceId;

    /** 任务状态: PENDING | PROCESSING | READY | ERROR */
    private String status;

    /** 错误原因（status=ERROR 时有值） */
    private String errorMsg;

    /** 信号数据列表（status=READY 时有值） */
    private List<SignalSeries> signals;

    /** 结果过期时间，UTC RFC3339 */
    private String expiresAt;

    /** 任务创建时间，UTC RFC3339 */
    private String createdAt;

    /**
     * 单个信号的时间序列
     */
    @Data
    public static class SignalSeries {

        /** 所属 Topic */
        private String topic;

        /** 信号标识名，如 "speed_mps" */
        private String signalName;

        /** 信号显示名称，如 "车速" */
        private String displayName;

        /** 物理单位，如 "m/s", "degrees" */
        private String unit;

        /** 时间序列数据点 */
        private List<SignalDataPoint> data;
    }

    /**
     * 信号数据点
     */
    @Data
    public static class SignalDataPoint {

        /** 时间戳，UTC RFC3339 */
        private String ts;

        /** 信号数值 */
        private Double value;
    }
}
