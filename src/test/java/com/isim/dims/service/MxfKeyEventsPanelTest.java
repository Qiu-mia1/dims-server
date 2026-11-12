package com.isim.dims.service;

import com.isim.dims.dto.MxfEventRecord;
import com.isim.dims.dto.MxfKeyEventsPanelResponse;
import com.isim.dims.entity.DataSlice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.Duration;
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
 * 「过去 24H 重点事件统计」单测（{@code getKeyEvents}）。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/key-events}。锁死：</p>
 * <ol>
 *   <li>固定 8 项、顺序即配置顺序、未命中补 0；</li>
 *   <li>窗口是<b>滚动 24 小时</b> {@code [now-24h, now)}（不是自然日，也不是 12H）；</li>
 *   <li>计数与明细解耦：明细被 recordLimit 截断时计数仍为真值；</li>
 *   <li>明细带 {@code system_name}（软件版本列）。</li>
 * </ol>
 *
 * @feature: mxf
 */
class MxfKeyEventsPanelTest extends MxfPanelTestSupport {

    @BeforeEach
    void useV6Events() {
        // v6 的 8 项（生产默认值）；共享夹具默认给的是 v5 的 6 项（旧 /window 用例要用）
        config.setEventOptions(new ArrayList<>(KEY_EVENTS_8));
        config.setDefaultEvents(new ArrayList<>(KEY_EVENTS_8));
    }

    // ============================================================
    // 1. 8 项固定
    // ============================================================

    @Test
    @DisplayName("固定 8 项、顺序即配置顺序，未命中补 0")
    void 事件_固定8项含0() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null);

        assertThat(resp.getEvents()).hasSize(8);
        assertThat(resp.getEvents()).extracting(MxfKeyEventsPanelResponse.EventCount::getEventName)
                .containsExactlyElementsOf(KEY_EVENTS_8);
        assertThat(resp.getEvents())
                .allSatisfy(e -> assertThat(e.getCount()).isZero());
        assertThat(resp.getWindow().getHours()).isEqualTo(24);
    }

    @Test
    @DisplayName("事件标题取中文（DSSAD 两条也不能漏）")
    void 事件_中文标题() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null);
        Map<String, String> titles = new LinkedHashMap<>();
        resp.getEvents().forEach(e -> titles.put(e.getEventName(), e.getTitle()));

        assertThat(titles.get("fushion_aeb")).isEqualTo("AEB触发风险");
        assertThat(titles.get("dssad_collision")).isEqualTo("DSSAD触发");
        assertThat(titles.get("unp_tkov_colli")).isEqualTo("UNP碰撞");
        assertThat(titles.get("meb_brake_event")).isEqualTo("LAEB触发");
    }

    @Test
    @DisplayName("计数来自聚合查询，不受明细行数上限影响")
    void 计数_不被limit截断() {
        givenCounts("unp_tkov_colli", 173);
        givenSlices(); // 明细为空

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", 5);

        assertThat(resp.getRecords()).isEmpty();
        assertThat(resp.getEvents()).filteredOn(e -> "unp_tkov_colli".equals(e.getEventName()))
                .singleElement()
                .extracting(MxfKeyEventsPanelResponse.EventCount::getCount)
                .isEqualTo(173L);
    }

    // ============================================================
    // 2. 窗口
    // ============================================================

    @Test
    @DisplayName("兼容模式：不传时间参数 → 滚动 24 小时 [now-24h, now)，preset=rolling24h")
    void 窗口_滚动24小时() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null);

        assertThat(resp.getWindow().getPreset()).isEqualTo("rolling24h");
        assertThat(resp.getWindow().getHours()).isEqualTo(24);
        assertThat(Duration.between(resp.getWindow().getStart(), resp.getWindow().getEnd()))
                .isEqualTo(Duration.ofHours(24));

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dataSliceMapper).selectKeyEventCounts(any(), any(), from.capture(), to.capture());

        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofHours(24));
        // to 就是「现在」（允许几秒执行误差）
        assertThat(Duration.between(to.getValue(), now()).abs()).isLessThan(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("兼容模式：只传 sourceType 不传时间参数，仍是滚动 24h（不静默变成「今天」）")
    void 窗口_只有来源仍是滚动24小时() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null, null, null, null);

        assertThat(resp.getWindow().getPreset()).isEqualTo("rolling24h");
        assertThat(Duration.between(resp.getWindow().getStart(), resp.getWindow().getEnd()))
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("今天 = 北京时间 [今天 00:00, 明天 00:00)")
    void 窗口_今天() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null, "today", null, null);

        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        assertThat(resp.getWindow().getPreset()).isEqualTo("today");
        assertWindow(today, today.plusDays(1));
    }

    @Test
    @DisplayName("本周 = 北京时间 [本周一 00:00, 下周一 00:00)")
    void 窗口_本周() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null, "week", null, null);

        LocalDate monday = LocalDate.now(DISPLAY_ZONE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(resp.getWindow().getPreset()).isEqualTo("week");
        assertWindow(monday, monday.plusWeeks(1));
    }

    @Test
    @DisplayName("本月 = 北京时间 [本月 1 日 00:00, 下月 1 日 00:00)")
    void 窗口_本月() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null, "month", null, null);

        LocalDate first = LocalDate.now(DISPLAY_ZONE).withDayOfMonth(1);
        assertThat(resp.getWindow().getPreset()).isEqualTo("month");
        assertWindow(first, first.plusMonths(1));
    }

    @Test
    @DisplayName("自定义：结束日整天计入（右开边界 = endDate+1 的 00:00）")
    void 窗口_自定义含结束日() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents(
                "MMT", null, "custom", "2026-09-23", "2026-09-29");

        assertThat(resp.getWindow().getPreset()).isEqualTo("custom");
        assertWindow(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("自定义起止颠倒 → 自动交换")
    void 窗口_自定义颠倒() {
        givenCounts();
        givenSlices();

        service.getKeyEvents("MMT", null, "custom", "2026-09-29", "2026-09-23");

        assertWindow(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("自定义只有一侧日期 → 退回 today（不报错）")
    void 窗口_自定义单侧回退() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents(
                "MMT", null, "custom", "2026-09-23", null);

        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        assertThat(resp.getWindow().getPreset()).isEqualTo("today");
        assertWindow(today, today.plusDays(1));
    }

    @Test
    @DisplayName("全部 = 1970/9999 哨兵，hours 无意义返回 null")
    void 窗口_全部() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null, "all", null, null);

        assertThat(resp.getWindow().getPreset()).isEqualTo("all");
        assertThat(resp.getWindow().getStart()).isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0));
        assertThat(resp.getWindow().getEnd()).isEqualTo(LocalDateTime.of(9999, 12, 31, 23, 59, 59));
        assertThat(resp.getWindow().getHours()).isNull();
    }

    @Test
    @DisplayName("非法 preset → 按 today")
    void 窗口_非法preset() {
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null, "decade", null, null);

        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        assertThat(resp.getWindow().getPreset()).isEqualTo("today");
        assertWindow(today, today.plusDays(1));
    }

    @Test
    @DisplayName("来源与窗口共存：两个条件一起下推 SQL")
    void 窗口_来源与窗口共存() {
        givenCounts();
        givenSlices();

        service.getKeyEvents("ZYT", 50, "week", null, null);

        verify(dataSliceMapper).selectKeyEventCounts(eq("ZYT"), any(), any(), any());
        verify(dataSliceMapper).selectKeyEventSlices(eq("ZYT"), any(), any(), any(), eq(50));
        LocalDate monday = LocalDate.now(DISPLAY_ZONE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertWindow(monday, monday.plusWeeks(1));
    }

    // ============================================================
    // 3. 明细（软件版本 / 排序 / 下载态）
    // ============================================================

    @Test
    @DisplayName("明细透出 system_name（软件版本列），空值保持 null 由前端兜 '-'")
    void 明细_软件版本() {
        givenCounts("unp_tkov_colli", 2);
        LocalDateTime t1 = now().minusHours(2);
        LocalDateTime t2 = now().minusHours(5);
        DataSlice withVer = slice("sl-1", VIN_A, "unp_tkov_colli", t1, t1);
        withVer.setSystemName("V2.3.1");
        DataSlice noVer = slice("sl-2", VIN_B, "unp_tkov_colli", t2, t2);
        givenSlices(withVer, noVer);

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null);

        assertThat(resp.getRecords()).extracting(MxfEventRecord::getSystemName)
                .containsExactly("V2.3.1", null);
    }

    @Test
    @DisplayName("明细按有效触发时刻倒序（SQL 已排序，服务层再兜一次）")
    void 明细_倒序() {
        givenCounts();
        LocalDateTime old = now().minusHours(20);
        LocalDateTime recent = now().minusHours(1);
        givenSlices(
                slice("sl-old", VIN_A, "system_crash", old, old),
                slice("sl-new", VIN_B, "system_crash", recent, recent));

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null);

        assertThat(resp.getRecords()).extracting(MxfEventRecord::getTriggerTime)
                .containsExactly(recent, old);
    }

    @Test
    @DisplayName("归档切片 downloadable=false，MINIO 切片可下载")
    void 明细_可下载标记() {
        givenCounts();
        LocalDateTime t = now().minusHours(1);
        DataSlice archived = archived(slice("sl-a", VIN_A, "system_crash", t, t));
        DataSlice live = slice("sl-b", VIN_B, "system_crash", t, t);
        givenSlices(archived, live);

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null);
        Map<String, Boolean> flags = new LinkedHashMap<>();
        resp.getRecords().forEach(r -> flags.put(r.getSliceId(), r.isDownloadable()));

        assertThat(flags.get("sl-a")).isFalse();
        assertThat(flags.get("sl-b")).isTrue();
    }

    // ============================================================
    // 4. 参数归一化
    // ============================================================

    @Test
    @DisplayName("recordLimit 夹到 [1,500]，空则用配置默认值 200")
    void 参数_recordLimit夹取() {
        givenCounts();
        givenSlices();

        service.getKeyEvents("MMT", 9999);
        service.getKeyEvents("MMT", 0);
        service.getKeyEvents("MMT", null);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(dataSliceMapper, org.mockito.Mockito.times(3))
                .selectKeyEventSlices(any(), any(), any(), any(), limit.capture());

        assertThat(limit.getAllValues()).containsExactly(500, 1, 200);
    }

    @Test
    @DisplayName("sourceType 下推；空 / all → 不过滤")
    void 参数_来源() {
        givenCounts();
        givenSlices();

        service.getKeyEvents("zyt", null);
        service.getKeyEvents("all", null);
        service.getKeyEvents(null, null);

        verify(dataSliceMapper).selectKeyEventCounts(eq("ZYT"), any(), any(), any());
        verify(dataSliceMapper, org.mockito.Mockito.times(2))
                .selectKeyEventCounts(isNull(), any(), any(), any());
    }

    @Test
    @DisplayName("events 清单为空时用兜底 8 项，绝不把空集合传给 IN ()")
    void 参数_事件清单兜底() {
        config.setEventOptions(new ArrayList<>());
        givenCounts();
        givenSlices();

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null);

        assertThat(resp.getEvents()).hasSize(8);
        ArgumentCaptor<List<String>> events = ArgumentCaptor.forClass(List.class);
        verify(dataSliceMapper).selectKeyEventCounts(any(), events.capture(), any(), any());
        assertThat(events.getValue()).isNotEmpty();
    }

    // ============================================================
    // 5. 空态 / 不越界
    // ============================================================

    @Test
    @DisplayName("空库：8 项全 0、明细空，不抛异常")
    void 空态() {
        when(dataSliceMapper.selectKeyEventCounts(any(), any(), any(), any())).thenReturn(null);
        when(dataSliceMapper.selectKeyEventSlices(any(), any(), any(), any(), anyInt())).thenReturn(null);

        MxfKeyEventsPanelResponse resp = service.getKeyEvents("MMT", null);

        assertThat(resp.getEvents()).hasSize(8);
        assertThat(resp.getRecords()).isEmpty();
        assertThat(resp.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("不读切片列表（新端点全部下推 SQL）")
    void 不查切片列表() {
        givenCounts();
        givenSlices();

        service.getKeyEvents("MMT", null);

        verify(dataSliceMapper, never()).selectDashboardRows(anyInt());
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 参数为 name, cnt 交替；无参 = 空分组结果 */
    private void givenCounts(Object... nameCountPairs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i + 1 < nameCountPairs.length; i += 2) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event_name", nameCountPairs[i]);
            row.put("cnt", ((Number) nameCountPairs[i + 1]).longValue());
            rows.add(row);
        }
        when(dataSliceMapper.selectKeyEventCounts(any(), any(), any(), any())).thenReturn(rows);
    }

    private void givenSlices(DataSlice... slices) {
        when(dataSliceMapper.selectKeyEventSlices(any(), any(), any(), any(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(slices)));
    }

    /**
     * 断言「最近一次请求」的窗口 = 北京 [startDay 00:00, endDay 00:00) 的 UTC 墙钟，
     * 且两个 Mapper 方法用的是同一个窗口（计数与明细不能各算各的）。
     */
    private void assertWindow(LocalDate startDay, LocalDate endDay) {
        LocalDateTime expectedFrom = beijingDayAt(startDay, 0);
        LocalDateTime expectedTo = beijingDayAt(endDay, 0);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dataSliceMapper, org.mockito.Mockito.atLeastOnce())
                .selectKeyEventCounts(any(), any(), from.capture(), to.capture());
        verify(dataSliceMapper, org.mockito.Mockito.atLeastOnce())
                .selectKeyEventSlices(any(), any(), from.capture(), to.capture(), anyInt());

        List<LocalDateTime> froms = from.getAllValues();
        List<LocalDateTime> tos = to.getAllValues();
        assertThat(froms.get(froms.size() - 1)).isEqualTo(expectedFrom);
        assertThat(tos.get(tos.size() - 1)).isEqualTo(expectedTo);
        // 每个被捕获的调用都必须是同一个窗口
        assertThat(froms).allSatisfy(v -> assertThat(v).isEqualTo(expectedFrom));
        assertThat(tos).allSatisfy(v -> assertThat(v).isEqualTo(expectedTo));
    }
}
