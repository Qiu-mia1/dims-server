package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 看板顶部 KPI 卡片。
 *
 * <p>字段名与前端 {@code MxfDashboard.vue} 的取数路径一一对应，
 * 修改任一侧必须同步另一侧（此前曾因命名不一致导致看板空白）。
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfKpiCardsResponse {

    /**
     * 数据包总数（当前时间筛选范围内）
     */
    private Long totalPackets;

    /**
     * 日志记录总条数（各包 record_count 汇总）
     */
    private Long totalRecords;

    /**
     * 辅助驾驶总里程（公里，各包 section_stats.distanceKm 汇总）
     */
    private Double totalDrivingDistanceKm;

    /**
     * 辅助驾驶总时长（小时，各包 section_stats.durationHours 汇总）
     */
    private Double totalDrivingDurationHour;

    /**
     * AEB 触发次数
     */
    private Long aebTriggerCount;

    /**
     * APA 触发次数
     */
    private Long apaTriggerCount;

    /**
     * AVP 激活次数
     */
    private Long avpActivationCount;

    /**
     * 特殊事件记录数（8 类特殊事件包的 record_count 汇总）
     */
    private Long specialEventCount;
}
