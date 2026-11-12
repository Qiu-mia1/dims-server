package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 触发类型下拉选项响应（数据浏览器筛选栏）。
 *
 * <p>对应 {@code GET /v1/data-slices/filter-options}。响应体走全局 SNAKE_CASE，
 * 实际 JSON：{@code {"filters":[{"filter":"...","event_names":[...],"titles":[...]}]}}。</p>
 *
 * <p>与智驾事件看板「筛选器」下拉同源（{@link com.isim.dims.config.CameraEventConfig}），
 * 只是去掉了看板响应里的趋势 / 明细等重字段 —— 本端点只读内存配置，不查库。</p>
 *
 * <p>前端用法：按 {@code filters} 分组渲染（{@code el-option-group}），组标题取
 * {@code filter}，组内每个事件一项，{@code label} 显示 {@code titles[i]}、
 * {@code value} 取 {@code event_names[i]}（请求实际发送的英文值）。</p>
 *
 * @feature: slice
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SliceFilterOptionsResponse {

    /** 全部筛选器选项（按 camera_event_config.json 的 events 顺序；配置加载失败时为空列表） */
    private List<FilterOption> filters;
}
