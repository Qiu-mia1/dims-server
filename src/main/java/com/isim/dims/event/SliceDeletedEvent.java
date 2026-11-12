package com.isim.dims.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据切片已删除事件 Payload
 *
 * <p>对应 dims.asyncapi.yaml SliceDeleted message。
 * 下游收到后应清理对该切片的引用。</p>
 *
 * <p>Kafka Topic: dims.slice.deleted.v1</p>
 *
 * @see SliceEventPublisher
 * @feature: slice
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SliceDeletedEvent {

    /** 被删除的切片唯一 ID（UUID） */
    private String sliceId;

    /** 删除时间，UTC RFC3339 */
    private String occurredAt;

    /** 删除原因：manual | expired | privacy_violation | quality_reject */
    private String reason;

    /** 链路追踪 ID */
    private String traceId;
}
