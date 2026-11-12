package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 事件分布柱状图条目。
 *
 * <p>对应看板"数据包总量"和"事件分布"两张图：
 * <ul>
 *   <li>sectionBar — 按 section 聚合（数据包总量柱状图）</li>
 *   <li>eventBar   — 按事件聚合（camera_event_config.json events 里定义的全部事件，
 *       触发次数由 .data 日志的 SECTION 统计解析）</li>
 * </ul>
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li>{@link #label}        —— 类别名（section 名 或 事件名，如 {@code fushion_aeb}）。
 *       保留原文用于 tooltip / 排序 / 回传服务端。</li>
 *   <li>{@link #eventTitle}   —— 中文展示标题，由 {@link com.isim.dims.util.MxfSpecialEvents#titleOf}
 *       计算。section 类（图1 "数据包总量"）下没有 event_name，{@code event_title} 为 null。
 *       前端展示优先用 {@code event_title}，缺失时回退到 {@code label}（与 data_slice 列表同语义）。</li>
 *   <li>{@link #count}        —— 数量</li>
 * </ul>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfEventDistributionItem {

    /**
     * 类别名（section 或 event_name）
     */
    private String label;

    /**
     * 中文展示标题（仅 event 类条目有值；section 类条目为 null，前端走 label fallback）。
     *
     * <p>非 null 且非空时优先渲染；空字符串视为缺失，前端会回退到 {@link #label}。
     * 该语义与 data_slice 列表的 {@code event_title || getEventTitle(event_name)} 保持一致。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String eventTitle;

    /**
     * 数量
     */
    private Long count;
}