package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运营日报看板 · 「日报摘要 + 事件类型分布」（同一统计窗口、同一次响应）。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/daily-report}。</p>
 *
 * <h3>为什么摘要与分布必须同响应</h3>
 * <p>摘要里的「共触发 N 次」与分布里 19 个桶的合计必须自洽；拆成两个请求后，
 * 只要两次查询之间落了一条新数据，页面上就会出现「摘要 517 次、各桶加起来 518 次」
 * 这种无法解释的差异。故合并为一个端点、两条 SQL、同一窗口。</p>
 *
 * <h3>口径</h3>
 * <ul>
 *   <li>{@code total_vehicles} = 窗口内 {@code COUNT(DISTINCT vin)}</li>
 *   <li>{@code total_files} = 窗口内切片数（无文件明细表，一行 data_slice = 一个数据包）</li>
 *   <li>{@code total_bytes} = 窗口内 {@code SUM(file_size)}</li>
 *   <li>{@code total_triggers} = 窗口内 event_name 非空且非 {@code obf_*} 的切片数（一个包最多 1 次）</li>
 *   <li>{@code unclassified_count = total_triggers − Σ(event_distribution[].count)}</li>
 * </ul>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfDailyReportResponse {

    /** 本次快照生成时间（UTC） */
    private LocalDateTime generatedAt;

    /** 实际生效的统计窗口 */
    private Range range;

    /** 摘要数字 */
    private Summary summary;

    /** 事件类型分布：固定 19 项、顺序固定、无值为 0 */
    private List<EventBucket> eventDistribution;

    /** 未归入 19 类的触发次数（obf_* / 空 event_name 等），前端以脚注「其他 N 次」展示 */
    private long unclassifiedCount;

    /**
     * 统计窗口（北京时间自然日换算后的 UTC 墙钟，左闭右开）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Range {
        /** today | week | month | all | custom */
        private String preset;
        /** 窗口起点（UTC，含） */
        private LocalDateTime start;
        /** 窗口终点（UTC，不含） */
        private LocalDateTime end;
    }

    /**
     * 摘要数字
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        /** 回传数据的车辆数（去重） */
        private long totalVehicles;
        /** 触发灵活采数事件次数（包数口径） */
        private long totalTriggers;
        /** 文件总数 = 数据条数 */
        private long totalFiles;
        /** 文件大小总计（字节） */
        private long totalBytes;
    }

    /**
     * 事件分布的一项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventBucket {
        /** 桶 key（{@code MxfDailyReportEvents} 的 19 个 key 之一） */
        private String key;
        /** 展示标题 */
        private String title;
        /** 命中包数（无值为 0） */
        private long count;
    }
}
