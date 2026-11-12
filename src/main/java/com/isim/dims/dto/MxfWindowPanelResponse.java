package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 单车看板 · 右列「指定日期全天重点事件统计」面板响应。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/window}。统计区间为**北京时间自然日**
 * {@code [date 00:00, date+1 00:00)}，按切片的有效触发时刻过滤；{@code window.start/end}
 * 已换算为 UTC 落地时间，供前端直接格式化展示。</p>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfWindowPanelResponse {

    /** 本次快照生成时间（UTC） */
    private LocalDateTime generatedAt;

    /** 统计窗口（北京时间某天的 00:00 ~ 次日 00:00） */
    private Window window;

    /** 可勾选的重点事件（配置的固定 6 个，保持配置顺序） */
    private List<EventOption> eventOptions;

    /** 当前勾选的重点事件名 */
    private List<String> selectedEvents;

    /** 窗口内统计（车辆数 / 触发次数 / 项目 / 按事件拆分） */
    private WindowStats windowStats;

    /** 窗口内明细（按有效触发时刻倒序，受 recordLimit 限制） */
    private List<MxfEventRecord> windowRecords;

    /**
     * 统计窗口
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Window {
        /** 窗口起点（UTC）= 统计日北京时间 00:00 */
        private LocalDateTime start;
        /** 窗口终点（UTC）= 次日北京时间 00:00，<b>区间不含该点</b>（左闭右开，日界不重复计入） */
        private LocalDateTime end;
        /** 统计日期（北京时间自然日），前端标题直接用它，不必再从 start 反推时区 */
        private LocalDate date;
        /** 窗口跨度小时数：自然日恒为 24，保留字段供前端展示/兼容 */
        private int hours;
    }

    /**
     * 可选事件项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventOption {
        /** 事件名（camera_event_config.json 的 key） */
        private String name;
        /** 展示标题（MxfSpecialEvents.titleOf，未知事件原样返回 event_name） */
        private String title;
        /** 是否默认勾选 */
        private boolean selected;
    }

    /**
     * 窗口统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WindowStats {
        /** 窗口内上传过数据的车辆数（不要求命中勾选事件） */
        private long totalVehicles;
        /** 窗口内勾选事件的记录数合计（口径 = 包数） */
        private long totalTriggers;
        /** 项目标识（窗口内最常见的 car_type + source 组合），无数据时为 null */
        private String project;
        /** 按事件拆分的次数（仅勾选事件） */
        private List<EventCount> byEvent;
    }

    /**
     * 事件计数项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventCount {
        /** 事件名 */
        private String eventName;
        /** 事件展示标题 */
        private String title;
        /** 记录数（口径 = 包数） */
        private long count;
    }
}
