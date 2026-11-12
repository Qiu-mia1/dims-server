package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运营日报看板 · 「重点事件统计」面板响应。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/key-events}。与 v5 的
 * {@code /window}（北京时间某一自然日 + 可勾选事件 + 饼图）不同：这里是
 * <b>固定 8 个事件</b>、<b>只有列表</b>（原型已去掉饼图与概述文字）。</p>
 *
 * <p>窗口跟随顶部时间范围，与「事件类型分布」<b>同一窗口</b>（北京时间自然日、左闭右开）；
 * 三个时间参数全空时退回 {@code rolling24h = [now − 24h, now)}，兼容旧调用。
 * 时间字段与看板其余面板一致，取 {@code COALESCE(trigger_time, start_ts, created_at)}。</p>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfKeyEventsPanelResponse {

    /** 本次快照生成时间（UTC） */
    private LocalDateTime generatedAt;

    /** 统计窗口 */
    private Window window;

    /** 固定 8 项事件计数：顺序即配置顺序、无值为 0（不受明细行数上限影响） */
    private List<EventCount> events;

    /** 明细行（按有效触发时刻倒序，受 recordLimit 限制） */
    private List<MxfEventRecord> records;

    /**
     * 统计窗口
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Window {
        /**
         * 窗口类型：{@code rolling24h}（兼容旧调用，[now-24h, now)）|
         * {@code today} | {@code week} | {@code month} | {@code all} | {@code custom}。
         *
         * <p>前端用它决定卡片标题（今天/本周/本月…），与 start/end 一起构成唯一事实源。</p>
         */
        private String preset;
        /** 起点（UTC，含） */
        private LocalDateTime start;
        /** 终点（UTC，不含） */
        private LocalDateTime end;
        /**
         * 窗口跨度（小时）：rolling24h/today=24、week=168、month=672/744、custom=N*24；
         * {@code all} 是 1970~9999 的哨兵窗口，跨度无意义 → {@code null}。
         *
         * @deprecated 前端请用 {@link #preset} 出文案、{@link #start}/{@link #end} 出副标题，
         *             不要再用 hours 反推窗口
         */
        @Deprecated
        private Integer hours;
    }

    /**
     * 单个事件的计数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventCount {
        /** 事件名（data_slice.event_name） */
        private String eventName;
        /** 展示标题 */
        private String title;
        /** 命中包数 */
        private long count;
    }
}
