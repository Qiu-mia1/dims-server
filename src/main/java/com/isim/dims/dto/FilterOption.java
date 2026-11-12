package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 触发类型选项 —— 一个筛选器（{@code camera_event_config.json} 的 {@code events.*.filter}）
 * 及其映射到的事件名。
 *
 * <p>唯一真相源：{@link com.isim.dims.config.CameraEventConfig#getFilterEventIndex()}
 * （筛选器 → 事件名，顺序即配置文件的 events 顺序）；中文标题来自
 * {@link com.isim.dims.util.MxfSpecialEvents#titleOf(String)}（Excel「枚举」列空白的事件
 * 原样返回事件名）。</p>
 *
 * <p>两处下拉共用本结构，保证「内容一样」：</p>
 * <ul>
 *   <li>单车/智驾事件看板「筛选器」→ {@link MxfFilterPanelResponse#getFilterOptions()}
 *       （另有同名嵌套类，字段结构一致）；</li>
 *   <li>数据浏览器「触发类型」→ {@code GET /v1/data-slices/filter-options}
 *       （{@link SliceFilterOptionsResponse}）。</li>
 * </ul>
 *
 * <p>⚠️ 一个筛选器可能对应多个事件（如 {@code highbeam_on_filter} → 高中光 + 中远光），
 * 所以 {@code eventNames} 恒为列表，且与 {@code titles} 一一对应（同下标）。</p>
 *
 * @feature: mxf, slice
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterOption {

    /**
     * 筛选器名（UI 分组标题）。
     *
     * <p>保持配置原文（英文，如 {@code highbeam_on_filter}）：一是与
     * {@code camera_event_config.json} / 看板筛选器下拉可直接对照，二是配置文件里
     * {@code highbeam_on_filter} 这类筛选器名本身没有中文枚举值。</p>
     */
    private String filter;

    /**
     * 该筛选器映射的事件名。
     *
     * <p>这才是数据侧真正落库的值：{@code data_slice.event_name} / {@code mxf_packet.event_name}
     * 存的就是这些（前端请求发送的也是它）。</p>
     */
    private List<String> eventNames;

    /** 与 {@link #eventNames} 一一对应的中文展示标题（未登记中文的原样返回事件名） */
    private List<String> titles;
}
