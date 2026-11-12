package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 看板顶部"特殊事件通知"条目。
 *
 * <p>来源：{@code data_slice} 表里 {@code event_name} 命中 6 类特殊事件
 * （imu_collision / system_crash / cp_aes_trig_event / cp_ess_trig_event /
 * unp_tkov_colli / fushion_aeb）的切片，最近 24 小时，按触发时间倒序。</p>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfSpecialEventAlert {

    /**
     * 数据切片 ID（data_slice.id）
     */
    private String id;

    /**
     * 所属数据切片 ID（同 id，保留字段便于前端区分语义）
     */
    private String packetId;

    /**
     * VIN
     */
    private String vin;

    /**
     * 触发类型（data_slice.event_name，如 {@code imu_collision}）
     */
    private String eventName;

    /**
     * 事件标题（人类可读，见 {@link com.isim.dims.util.MxfSpecialEvents#titleOf}）
     */
    private String title;

    /**
     * 触发时间（UTC）；{@code trigger_time} 为空时取切片 {@code start_ts}
     */
    private LocalDateTime triggerTime;
}
