package com.isim.dims.service;

import com.isim.dims.dto.MxfDailyReportResponse;
import com.isim.dims.util.MxfDailyReportEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日报「摘要 + 19 类事件分布」单测（{@code getDailyReport}）。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/daily-report}。锁死四件事：</p>
 * <ol>
 *   <li>分布固定 19 项、顺序固定、无值为 0（前端按索引取色）；</li>
 *   <li>{@code Σ(19 桶) + 未归类 == 总触发}（用减法算未归类，兜住 obf_* / 空事件名）；</li>
 *   <li>北京时间自然日、左闭右开（today/week/month/自定义）；</li>
 *   <li>全部下推 SQL，不再走 {@code selectDashboardRows}（那是 LIMIT 5000 的内存截断）。</li>
 * </ol>
 *
 * @feature: mxf
 */
class MxfDailyReportTest extends MxfPanelTestSupport {

    private static final int BUCKET_COUNT = 19;

    // ============================================================
    // 1. 19 类分布
    // ============================================================

    @Test
    @DisplayName("分布固定 19 项、顺序与 MxfDailyReportEvents 一致，无值补 0")
    void 分布_固定19项含0() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        MxfDailyReportResponse resp = service.getDailyReport("MMT", "today", null, null);

        assertThat(resp.getEventDistribution()).hasSize(BUCKET_COUNT);
        assertThat(resp.getEventDistribution()).extracting(MxfDailyReportResponse.EventBucket::getKey)
                .containsExactlyElementsOf(MxfDailyReportEvents.keySet());
        assertThat(resp.getEventDistribution())
                .allSatisfy(b -> assertThat(b.getCount()).isZero());
        assertThat(resp.getUnclassifiedCount()).isZero();
    }

    @Test
    @DisplayName("按原始 event_name 归桶：合并桶求和、未登记事件计入 unclassified")
    void 分布_归桶与未归类() {
        givenTotals(120, 7, 6_000_000_000L, 17);
        givenEventCounts(
                "unp_tkov_colli", 5,
                "meb_brake_event", 3,
                "high_highbeam_on", 2,
                "mid_highbeam_on", 1,
                "apa_all_time_event_segment", 4,
                "mystery_event", 2);

        MxfDailyReportResponse resp = service.getDailyReport("MMT", "today", null, null);
        Map<String, Long> byKey = new LinkedHashMap<>();
        resp.getEventDistribution().forEach(b -> byKey.put(b.getKey(), b.getCount()));

        assertThat(byKey.get("unp_tkov_colli")).isEqualTo(5L);
        assertThat(byKey.get("meb_brake_event")).isEqualTo(3L);
        // 两个 event_name 合并成一个展示类目
        assertThat(byKey.get("highbeam_on")).isEqualTo(3L);
        // _segment 后缀归到同一个 APA 桶
        assertThat(byKey.get("apa_all_time_event")).isEqualTo(4L);
        assertThat(byKey.get("system_crash")).isZero();

        // Σ(19 桶)=15，总触发 17 → 未归类 2（mystery_event）
        long bucketed = byKey.values().stream().mapToLong(Long::longValue).sum();
        assertThat(bucketed).isEqualTo(15L);
        assertThat(resp.getUnclassifiedCount()).isEqualTo(2L);
        assertThat(bucketed + resp.getUnclassifiedCount())
                .isEqualTo(resp.getSummary().getTotalTriggers());
    }

    @Test
    @DisplayName("摘要四项数字取自 totals 查询（文件数=行数、大小=SUM、台数=去重 VIN）")
    void 摘要_四项数字() {
        givenTotals(1873, 24, 7_058_000_000_000L, 517);
        givenEventCounts("unp_tkov_colli", 517);

        MxfDailyReportResponse resp = service.getDailyReport("MMT", "today", null, null);

        assertThat(resp.getSummary().getTotalFiles()).isEqualTo(1873L);
        assertThat(resp.getSummary().getTotalVehicles()).isEqualTo(24L);
        assertThat(resp.getSummary().getTotalBytes()).isEqualTo(7_058_000_000_000L);
        assertThat(resp.getSummary().getTotalTriggers()).isEqualTo(517L);
        assertThat(resp.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("空库：19 项全 0，不抛异常")
    void 空态() {
        when(dataSliceMapper.selectDailyReportTotals(any(), any(), any()))
                .thenReturn(new ArrayList<>());
        when(dataSliceMapper.selectDailyReportEventCounts(any(), any(), any()))
                .thenReturn(null);

        MxfDailyReportResponse resp = service.getDailyReport(null, "today", null, null);

        assertThat(resp.getEventDistribution()).hasSize(BUCKET_COUNT);
        assertThat(resp.getSummary().getTotalFiles()).isZero();
        assertThat(resp.getSummary().getTotalTriggers()).isZero();
        assertThat(resp.getUnclassifiedCount()).isZero();
    }

    // ============================================================
    // 2. 来源过滤
    // ============================================================

    @Test
    @DisplayName("项目下拉：sourceType 下推 SQL，大小写不敏感地对齐到 MMT/ZYT/Hirain")
    void 来源_下推与归一化() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        service.getDailyReport("zyt", "today", null, null);

        verify(dataSliceMapper).selectDailyReportTotals(eq("ZYT"), any(), any());
        verify(dataSliceMapper).selectDailyReportEventCounts(eq("ZYT"), any(), any());
    }

    @Test
    @DisplayName("sourceType 为空 / all / 非法值 → 不过滤（下发 null）")
    void 来源_不过滤() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        service.getDailyReport(" ", "today", null, null);
        service.getDailyReport("all", "today", null, null);
        service.getDailyReport("ABC", "today", null, null);

        verify(dataSliceMapper, org.mockito.Mockito.times(3))
                .selectDailyReportTotals(isNull(), any(), any());
    }

    // ============================================================
    // 3. 时间窗口（北京时间自然日、左闭右开）
    // ============================================================

    @Test
    @DisplayName("today = 北京时间 [今天 00:00, 明天 00:00)")
    void 窗口_今天() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        MxfDailyReportResponse resp = service.getDailyReport("MMT", "today", null, null);

        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        assertThat(resp.getRange().getPreset()).isEqualTo("today");
        assertWindow(today, today.plusDays(1));
    }

    @Test
    @DisplayName("week = 北京时间 [本周一 00:00, 下周一 00:00)")
    void 窗口_本周() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        MxfDailyReportResponse resp = service.getDailyReport("MMT", "week", null, null);

        LocalDate monday = LocalDate.now(DISPLAY_ZONE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(resp.getRange().getPreset()).isEqualTo("week");
        assertWindow(monday, monday.plusWeeks(1));
    }

    @Test
    @DisplayName("month = 北京时间 [本月 1 日 00:00, 下月 1 日 00:00)")
    void 窗口_本月() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        MxfDailyReportResponse resp = service.getDailyReport("MMT", "month", null, null);

        LocalDate first = LocalDate.now(DISPLAY_ZONE).withDayOfMonth(1);
        assertThat(resp.getRange().getPreset()).isEqualTo("month");
        assertWindow(first, first.plusMonths(1));
    }

    @Test
    @DisplayName("自定义：结束日当天整天计入（左闭右开 = endDate+1 的 00:00）")
    void 窗口_自定义含结束日() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        MxfDailyReportResponse resp = service.getDailyReport(
                "MMT", "custom", "2026-09-23", "2026-09-29");

        assertThat(resp.getRange().getPreset()).isEqualTo("custom");
        assertWindow(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("自定义起止颠倒 → 自动交换（不报错、不返回空窗口）")
    void 窗口_自定义颠倒() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        service.getDailyReport("MMT", "custom", "2026-09-29", "2026-09-23");

        assertWindow(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("自定义日期只有一侧 / 格式非法 → 退回 preset（默认 today）")
    void 窗口_自定义不完整回退() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        service.getDailyReport("MMT", "custom", "2026-09-23", null);
        service.getDailyReport("MMT", "custom", "2026/09/23", "2026-09-29");

        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        assertWindow(today, today.plusDays(1));
    }

    @Test
    @DisplayName("非法 timeRange → 按 today")
    void 窗口_非法preset() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        MxfDailyReportResponse resp = service.getDailyReport("MMT", "decade", null, null);

        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        assertThat(resp.getRange().getPreset()).isEqualTo("today");
        assertWindow(today, today.plusDays(1));
    }

    @Test
    @DisplayName("all = 1970/9999 哨兵（不传 null，避免 SQL 里再拼 if）")
    void 窗口_全部() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        MxfDailyReportResponse resp = service.getDailyReport("MMT", "all", null, null);

        assertThat(resp.getRange().getPreset()).isEqualTo("all");
        assertThat(resp.getRange().getStart()).isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0));
        assertThat(resp.getRange().getEnd()).isEqualTo(LocalDateTime.of(9999, 12, 31, 23, 59, 59));
    }

    // ============================================================
    // 4. 不越界
    // ============================================================

    @Test
    @DisplayName("不读切片列表（LIMIT 5000 的内存截断在这条链路上必须彻底绕开）")
    void 不查切片列表() {
        givenTotals(0, 0, 0, 0);
        givenEventCounts();

        service.getDailyReport("MMT", "all", null, null);

        verify(dataSliceMapper, never()).selectDashboardRows(anyInt());
    }

    // ============================================================
    // 夹具 / 断言
    // ============================================================

    private void givenTotals(long files, long vehicles, long bytes, long triggers) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total_files", files);
        row.put("total_vehicles", vehicles);
        row.put("total_bytes", bytes);
        row.put("total_triggers", triggers);
        when(dataSliceMapper.selectDailyReportTotals(any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(row)));
    }

    /** 参数为 name, cnt 交替；无参 = 空分组结果 */
    private void givenEventCounts(Object... nameCountPairs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i + 1 < nameCountPairs.length; i += 2) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event_name", nameCountPairs[i]);
            row.put("cnt", ((Number) nameCountPairs[i + 1]).longValue());
            rows.add(row);
        }
        when(dataSliceMapper.selectDailyReportEventCounts(any(), any(), any())).thenReturn(rows);
    }

    /** 断言最近一次 totals 查询的窗口 = 北京 [startDay 00:00, endDay 00:00) 的 UTC 墙钟 */
    private void assertWindow(LocalDate startDay, LocalDate endDay) {
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dataSliceMapper, org.mockito.Mockito.atLeastOnce())
                .selectDailyReportTotals(any(), from.capture(), to.capture());

        List<LocalDateTime> froms = from.getAllValues();
        List<LocalDateTime> tos = to.getAllValues();
        LocalDateTime lastFrom = froms.get(froms.size() - 1);
        LocalDateTime lastTo = tos.get(tos.size() - 1);

        assertThat(lastFrom).isEqualTo(beijingDayAt(startDay, 0));
        assertThat(lastTo).isEqualTo(beijingDayAt(endDay, 0));
    }
}
