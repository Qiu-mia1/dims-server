package com.isim.dims.event;

import com.isim.dims.dto.GpsBbox;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 数据切片已创建事件 Payload
 *
 * <p>对应 dims.asyncapi.yaml SliceCreated message。
 * 携带足够元数据供下游无需回调即可过滤。</p>
 *
 * <p>Kafka Topic: dims.slice.created.v1</p>
 *
 * @see SliceEventPublisher
 * @feature: slice
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SliceCreatedEvent {

    /** 切片唯一 ID（UUID） */
    private String sliceId;

    /** 事件发生时间，UTC RFC3339 */
    private String occurredAt;

    /** 切片名称 */
    private String name;

    /** 传感器类型列表 */
    private List<String> sensorTypes;

    /** 数据起始时间，UTC RFC3339 */
    private String startTs;

    /** 数据结束时间，UTC RFC3339 */
    private String endTs;

    /** 数据时长（毫秒） */
    private Long durationMs;

    /** GPS 地理包围盒，WGS84 坐标系（可选） */
    private GpsBbox gpsBbox;

    /** 标签名列表 */
    private List<String> tags;

    /** 总消息数 */
    private Long msgCountTotal;

    /** 链路追踪 ID */
    private String traceId;

    /** 切片详情 API 路径 */
    private String detailUrl;
}
