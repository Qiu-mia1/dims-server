package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单车看板 · 中上「筛选器视角」面板响应。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/filter}。下拉展示的是
 * {@code camera_event_config.json} 的 {@code events.*.filter} 名；一个筛选器可能对应
 * 多个 {@code event_name}（如 {@code highbeam_on_filter} → 高中/中远光），
 * 服务层解析成事件名集合后对 {@code mxf_packet.event_name} 取并集过滤。</p>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfFilterPanelResponse {

    /** 本次快照生成时间（UTC） */
    private LocalDateTime generatedAt;

    /** 本次趋势曲线的天数 */
    private int trendDays;

    /** 可选筛选器（按配置顺序去重） */
    private List<FilterOption> filterOptions;

    /** 当前选中的筛选器名（前端下拉回填值） */
    private String selectedFilter;

    /** 当前筛选器实际用于过滤的事件名集合（可能多个） */
    private List<String> selectedFilterEvents;

    /**
     * 单值兼容字段：仅当 {@link #selectedFilterEvents} 只有一个元素时给值，否则为 null。
     *
     * <p>保留是为了兼容仍按「单个 event_name」传参的调用方。</p>
     */
    private String selectedEvent;

    /** 选中筛选器命中的包按天趋势（包数） */
    private List<MxfTrendPoint> eventTrend;

    /** 选中筛选器的明细（按有效触发时刻倒序，受 recordLimit 限制） */
    private List<MxfEventRecord> eventRecords;

    /**
     * 可选筛选器项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterOption {
        /** 筛选器名（camera_event_config.json 的 {@code events.*.filter}，UI 直接展示该值） */
        private String filter;
        /** 映射到的事件名（一个筛选器可能对应多个事件，保持配置顺序） */
        private List<String> eventNames;
        /** 与 {@link #eventNames} 一一对应的展示标题（未知事件原样返回 event_name） */
        private List<String> titles;
    }
}
