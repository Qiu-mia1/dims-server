package com.isim.dims.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据切片生命周期事件推送器
 *
 * <p>封装 Kafka 事件发送逻辑，Kafka 不可用时降级为日志警告，不影响主流程。</p>
 *
 * <p>Topic 命名遵循宪法 0.3 节：dims.slice.created.v1 / dims.slice.deleted.v1</p>
 * @feature: slice
 */
@Slf4j
@Component
public class SliceEventPublisher {

    private static final String TOPIC_SLICE_CREATED = "dims.slice.created.v1";
    private static final String TOPIC_SLICE_DELETED = "dims.slice.deleted.v1";

    /**
     * required = false: 当 KafkaConfig 因 @ConditionalOnProperty 未生效时，
     * KafkaTemplate bean 不存在，注入 null 避免启动失败
     */
    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 发布 dims.slice.created.v1 事件
     *
     * @param event 切片创建事件 payload
     */
    public void publishSliceCreated(SliceCreatedEvent event) {
        if (!isKafkaAvailable()) {
            log.warn("Kafka unavailable, skip publishing SliceCreatedEvent: sliceId={}", event.getSliceId());
            return;
        }
        try {
            kafkaTemplate.send(TOPIC_SLICE_CREATED, event);
            log.info("Published SliceCreatedEvent: sliceId={}, topic={}", event.getSliceId(), TOPIC_SLICE_CREATED);
        } catch (Exception e) {
            log.error("Failed to publish SliceCreatedEvent: sliceId={}", event.getSliceId(), e);
        }
    }

    /**
     * 发布 dims.slice.deleted.v1 事件
     *
     * @param event 切片删除事件 payload
     */
    public void publishSliceDeleted(SliceDeletedEvent event) {
        if (!isKafkaAvailable()) {
            log.warn("Kafka unavailable, skip publishing SliceDeletedEvent: sliceId={}", event.getSliceId());
            return;
        }
        try {
            kafkaTemplate.send(TOPIC_SLICE_DELETED, event);
            log.info("Published SliceDeletedEvent: sliceId={}, topic={}", event.getSliceId(), TOPIC_SLICE_DELETED);
        } catch (Exception e) {
            log.error("Failed to publish SliceDeletedEvent: sliceId={}", event.getSliceId(), e);
        }
    }

    private boolean isKafkaAvailable() {
        return kafkaTemplate != null;
    }
}
