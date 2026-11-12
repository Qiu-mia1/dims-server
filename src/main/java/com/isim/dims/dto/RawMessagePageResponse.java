package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Raw 消息分页响应
 *
 * 对应 OpenAPI schema: RawMessagePageResponse。
 * required: [topic, type, total, messages, next_offset, has_payload]
 * @feature: visualization
 */
@Data
public class RawMessagePageResponse {

    /** 请求的 ROS Topic */
    private String topic;

    /** ROS 消息类型，如 "perception_interface_msgs/msg/RadarPerceptionResult" */
    private String type;

    /** 时间区间内该 topic 消息总数 */
    private Long total;

    /** 消息列表 */
    private List<RawMessageItem> messages;

    /** 下一页 offset（null 表示无更多） */
    private Integer nextOffset;

    /** 该 topic 是否含业务负载（true 恒为真，被排除 topic 直接 422 不返回本响应） */
    private boolean hasPayload = true;

    /**
     * 单条原始消息
     *
     * 对应 OpenAPI schema: RawMessageItem。
     * required: [seq, stamp, stamp_ns, data]
     */
    @Data
    public static class RawMessageItem {

        /** 该消息在 [start_ts, end_ts] 区间结果中的序号（从 1 起，与 offset 分页对应） */
        private Integer seq;

        /** bag 时间戳（epoch），UTC RFC3339；跨 topic 同步一律用此字段 */
        private String stamp;

        /** bag 时间戳纳秒整数，unit: ns（与 stamp 同一时刻，用于精确排序/定位） */
        private Long stampNs;

        /** 原始消息 JSON 序列化（标量/数组/嵌套），不做业务解释 */
        private Object data;

        /** 消息体 header.stamp 对应的纳秒值（上电相对时间，仅调试参考），unit: ns */
        private Long headerStampNs;

        /** 消息体 meta.sensor_timestamp_us（上电相对时间，仅调试参考），unit: us */
        private Long sensorTimestampUs;
    }
}
