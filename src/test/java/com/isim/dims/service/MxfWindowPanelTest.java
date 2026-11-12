package com.isim.dims.service;

import com.isim.dims.dto.MxfEventRecord;
import com.isim.dims.dto.MxfWindowPanelResponse;
import com.isim.dims.dto.MxfWindowPanelResponse.EventCount;
import com.isim.dims.dto.MxfWindowPanelResponse.EventOption;
import com.isim.dims.entity.DataSlice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 右列面板「指定日期全天重点事件统计」单测（{@code getWindowPanel}）。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/window}。统计区间 = <b>北京时间自然日</b>
 * {@code [date 00:00, date+1 00:00)}，按切片的有效触发时刻过滤，次数口径 = 切片数
 * （一个切片最多命中 1 次）；{@code date} 空 / 非法则回退「昨天」。</p>
 *
 * <p>用例里的时间一律写成「北京时间某天某点」（{@link #windowDayAt(int)} /
 * {@link #beijingDayAt(LocalDate, int)}），换算成 UTC 由夹具负责 —— 直接写 UTC 值
 * 会让「这个点到底属于哪一天」变得不可读，也正是本次改造要锁住的口径。</p>
 *
 * <p>数据源 {@code data_slice}：项目标识取 {@code slice_stats.vehicle_type + source_type}。</p>
 *
 * @feature: mxf
 */
class MxfWindowPanelTest extends MxfPanelTestSupport {

    // ============================================================
    // 1. 可勾选事件（固定 6 个）
    // ============================================================

    @Test
    @DisplayName("事件选项恰好 6 个固定值，默认全勾")
    void 事件选项为六个固定值() {
        givenSlices(List.of());

        MxfWindowPanelResponse resp = service.getWindowPanel(null, null, null);

        assertThat(resp.getEventOptions()).extracting(EventOption::getName)
                .containsExactlyElementsOf(DASHBOARD_EVENTS);
        assertThat(resp.getSelectedEvents()).containsExactlyElementsOf(DASHBOARD_EVENTS);
        assertThat(resp.getEventOptions()).allSatisfy(o -> assertThat(o.isSelected()).isTrue());
        // 22 个全量事件里未被配置的（如 dssad_collision）不出现
        assertThat(resp.getEventOptions()).extracting(EventOption::getName)
                .doesNotContain("dssad_collision", "lpnp_takeover", "high_highbeam_on");
    }

    @Test
    @DisplayName("显式传入的事件名：合法保留、未知丢弃；全未知则回退默认")
    void 事件传参校验() {
        givenSlices(List.of());

        // fushion_aeb 在 camera_event_config.json 里 → 保留
        MxfWindowPanelResponse mixed =
                service.getWindowPanel(List.of("not_an_event", "fushion_aeb"), null, null);
        assertThat(mixed.getSelectedEvents()).containsExactly("fushion_aeb");

        // 全部未知 → 回退配置默认（6 个）
        MxfWindowPanelResponse fallback =
                service.getWindowPanel(List.of("not_an_event"), null, null);
        assertThat(fallback.getSelectedEvents()).containsExactlyElementsOf(DASHBOARD_EVENTS);
    }

    // ============================================================
    // 2. 统计日期（date 参数）与窗口边界
    // ============================================================

    @Test
    @DisplayName("date 参数：合法值按该日；空 / 空白 / 格式非法 / 年份越界 一律回退昨天")
    void 日期参数解析() {
        givenSlices(List.of());

        // 合法 → 用该日
        assertThat(service.getWindowPanel(null, "2026-03-04", null).getWindow().getDate())
                .isEqualTo(LocalDate.of(2026, 3, 4));
        // 带首尾空白也认
        assertThat(service.getWindowPanel(null, " 2026-03-04 ", null).getWindow().getDate())
                .isEqualTo(LocalDate.of(2026, 3, 4));

        // 空 / 空白 → 昨天（配置 default-date-offset-days=1）
        assertThat(service.getWindowPanel(null, null, null).getWindow().getDate()).isEqualTo(WINDOW_DAY);
        assertThat(service.getWindowPanel(null, "   ", null).getWindow().getDate()).isEqualTo(WINDOW_DAY);

        // 格式非法：斜杠、月日不补零、非日期串 → 全部回退，不抛异常
        for (String bad : List.of("2026/03/04", "2026-3-4", "not-a-date", "20260304")) {
            assertThat(service.getWindowPanel(null, bad, null).getWindow().getDate())
                    .as("date=%s 应回退到昨天", bad)
                    .isEqualTo(WINDOW_DAY);
        }

        // 年份越界：1970 坏值 / 太远的未来 → 回退
        assertThat(service.getWindowPanel(null, "1970-01-01", null).getWindow().getDate())
                .isEqualTo(WINDOW_DAY);
        assertThat(service.getWindowPanel(null, "2999-01-01", null).getWindow().getDate())
                .isEqualTo(WINDOW_DAY);
    }

    @Test
    @DisplayName("窗口恒为整天 24 小时，start/end = 北京该日 00:00 ~ 次日 00:00 对应的 UTC 值")
    void 窗口为整天且边界换算到UTC() {
        givenSlices(List.of());

        MxfWindowPanelResponse resp = service.getWindowPanel(null, null, null);

        assertThat(resp.getWindow().getDate()).isEqualTo(WINDOW_DAY);
        assertThat(resp.getWindow().getHours()).isEqualTo(24);
        assertThat(resp.getWindow().getStart()).isEqualTo(beijingDayAt(WINDOW_DAY, 0));
        assertThat(resp.getWindow().getEnd()).isEqualTo(beijingDayAt(WINDOW_DAY.plusDays(1), 0));
        // 京沪无夏令时，起止相差恰好 24 小时
        assertThat(resp.getWindow().getEnd()).isEqualTo(resp.getWindow().getStart().plusHours(24));
    }

    @Test
    @DisplayName("窗口边界按北京时间切天：当日 00:00 计入；次日 00:00 与前一日 23:00 都不计入")
    void 窗口边界按北京时间切天() {
        givenSlices(List.of(
                // 北京当日 00:00 —— 窗口起点，含
                slice("dayStart", VIN_A, "fushion_aeb", windowDayAt(0), windowDayAt(0)),
                // 北京次日 00:00 —— 窗口终点，左闭右开故不含
                slice("nextDayStart", VIN_A, "fushion_aeb",
                        beijingDayAt(WINDOW_DAY.plusDays(1), 0), beijingDayAt(WINDOW_DAY.plusDays(1), 0)),
                // 北京前一日 23:00 —— 差 1 小时，不在窗口内
                slice("prevDayEnd", VIN_A, "fushion_aeb",
                        beijingDayAt(WINDOW_DAY.minusDays(1), 23), beijingDayAt(WINDOW_DAY.minusDays(1), 23))));

        MxfWindowPanelResponse resp = service.getWindowPanel(null, null, null);

        assertThat(resp.getWindowRecords()).extracting(MxfEventRecord::getSliceId)
                .containsExactly("dayStart");
    }

    @Test
    @DisplayName("跨时区归属：北京当日 07:00（UTC 前一日 23:00）算作该日 —— 若按 UTC 切天会被漏掉")
    void 窗口按北京时区归属而非UTC() {
        givenSlices(List.of(beijingEarlyMorningSlice()));

        MxfWindowPanelResponse resp = service.getWindowPanel(null, null, null);

        assertThat(resp.getWindowRecords()).extracting(MxfEventRecord::getSliceId)
                .containsExactly("beijing-early");
    }

    /** 北京当日 07:00 的切片：换算成 UTC 会落到「前一日」，专门用来暴露「按 UTC 切天」的写法 */
    private static DataSlice beijingEarlyMorningSlice() {
        LocalDateTime utc = windowDayAt(7);
        // 前提校验：该 UTC 日期确实早于窗口日 —— 否则这条用例白测
        assertThat(utc.toLocalDate()).isBefore(WINDOW_DAY);
        return slice("beijing-early", VIN_A, "fushion_aeb", utc, utc);
    }

    // ============================================================
    // 3. 全天统计（口径 = 切片）
    // ============================================================

    @Test
    @DisplayName("事件计数按切片：event_name 命中的切片各计 1 次，event_name 为 null 的切片不计")
    void 事件计数按切片级eventName() {
        givenSlices(List.of(
                slice("p1", VIN_A, "fushion_aeb", windowDayAt(20), windowDayAt(20)),
                slice("p2", VIN_B, "fushion_aeb", windowDayAt(10), windowDayAt(10)),
                // 切片没有触发事件 → event_name 为 null，任何事件都不命中
                slice("p3", VIN_C, null, windowDayAt(3), windowDayAt(3))));

        MxfWindowPanelResponse resp = service.getWindowPanel(
                List.of("fushion_aeb", "cp_aes_trig_event"), null, null);

        assertThat(resp.getWindowStats().getTotalVehicles()).isEqualTo(3L);
        // 2 个切片命中 fushion_aeb（每片 1 次），cp_aes_trig_event 0 次
        assertThat(resp.getWindowStats().getTotalTriggers()).isEqualTo(2L);
        assertThat(resp.getWindowStats().getByEvent())
                .extracting(EventCount::getEventName, EventCount::getCount)
                .containsExactly(
                        tuple("fushion_aeb", 2L),
                        tuple("cp_aes_trig_event", 0L));
        assertThat(resp.getWindowRecords()).hasSize(2);
        assertThat(resp.getWindowRecords())
                .allSatisfy(r -> assertThat(r.getEventName()).isEqualTo("fushion_aeb"));
    }

    @Test
    @DisplayName("全天过滤：只统计该日 24 小时内的切片（按有效触发时刻）")
    void 全天过滤() {
        givenSlices(List.of(
                slice("in", VIN_A, "fushion_aeb", windowDayAt(12), windowDayAt(12)),
                slice("prevDay", VIN_A, "fushion_aeb",
                        beijingDayAt(WINDOW_DAY.minusDays(1), 12), beijingDayAt(WINDOW_DAY.minusDays(1), 12))));

        MxfWindowPanelResponse resp = service.getWindowPanel(null, null, null);

        assertThat(resp.getWindowStats().getTotalTriggers()).isEqualTo(1L);
        assertThat(resp.getWindowRecords()).hasSize(1);
        assertThat(resp.getWindowRecords().get(0).getSliceId()).isEqualTo("in");
    }

    @Test
    @DisplayName("显式指定 date 时统计该日：昨天数据落在指定日窗口外 → 不统计")
    void 指定日期改变统计范围() {
        givenSlices(List.of(
                slice("yesterday", VIN_A, "fushion_aeb", windowDayAt(12), windowDayAt(12))));

        // 指定「前天」→ 昨天的切片不该出现
        String twoDaysAgo = WINDOW_DAY.minusDays(1).toString();
        MxfWindowPanelResponse resp = service.getWindowPanel(null, twoDaysAgo, null);

        assertThat(resp.getWindow().getDate()).isEqualTo(WINDOW_DAY.minusDays(1));
        assertThat(resp.getWindowRecords()).isEmpty();
        assertThat(resp.getWindowStats().getTotalTriggers()).isZero();
    }

    @Test
    @DisplayName("trigger_time 为空时全天窗口按 start_ts 过滤（需求方 2026-09-18 口径）")
    void 全天按startTs兜底过滤() {
        givenSlices(List.of(
                // 入库时间在窗口外，但 start_ts 在窗口内 → 应被算进来
                sliceFull("in", VIN_A, "fushion_aeb",
                        beijingDayAt(WINDOW_DAY.minusDays(5), 3), null, windowDayAt(2)),
                // start_ts 也在窗口外 → 不该进来
                sliceFull("out", VIN_A, "fushion_aeb",
                        beijingDayAt(WINDOW_DAY.minusDays(5), 3), null,
                        beijingDayAt(WINDOW_DAY.minusDays(1), 20))));

        MxfWindowPanelResponse resp = service.getWindowPanel(null, null, null);

        assertThat(resp.getWindowRecords()).extracting(MxfEventRecord::getSliceId)
                .containsExactly("in");
    }

    @Test
    @DisplayName("项目标识取窗口内出现最多的「车型 + 来源」组合（车型来自 slice_stats）")
    void 项目标识() {
        givenSlices(List.of(
                slice("p1", VIN_A, "fushion_aeb", windowDayAt(20), windowDayAt(20)),
                slice("p2", VIN_B, "fushion_aeb", windowDayAt(10), windowDayAt(10))));

        MxfWindowPanelResponse resp = service.getWindowPanel(null, null, null);

        assertThat(resp.getWindowStats().getProject()).isEqualTo("CX835ICA MMT");
    }

    @Test
    @DisplayName("车型缺失（无 slice_stats 记录）时项目标识退化为来源，全空则为 null")
    void 项目标识降级() {
        var noVehicleType = slice("p1", VIN_A, "fushion_aeb", windowDayAt(20), windowDayAt(20));
        noVehicleType.setVehicleType(null);
        givenSlices(List.of(noVehicleType));

        assertThat(service.getWindowPanel(null, null, null).getWindowStats().getProject())
                .isEqualTo("MMT");

        var blank = slice("p2", VIN_A, "fushion_aeb", windowDayAt(20), windowDayAt(20));
        blank.setVehicleType(null);
        blank.setSourceType(null);
        givenSlices(List.of(blank));

        assertThat(service.getWindowPanel(null, null, null).getWindowStats().getProject()).isNull();
    }

    // ============================================================
    // 4. 明细：排序 / 行数限制 / 空态
    // ============================================================

    @Test
    @DisplayName("窗口明细按有效触发时刻倒序")
    void 窗口明细倒序() {
        givenSlices(List.of(
                slice("oldest", VIN_A, "fushion_aeb", windowDayAt(3), windowDayAt(2)),
                slice("newest", VIN_B, "fushion_aeb", windowDayAt(19), windowDayAt(20)),
                slice("middle", VIN_C, "fushion_aeb", windowDayAt(10), windowDayAt(9))));

        MxfWindowPanelResponse resp = service.getWindowPanel(null, null, null);

        assertThat(resp.getWindowRecords()).extracting(MxfEventRecord::getSliceId)
                .containsExactly("newest", "middle", "oldest");
    }

    @Test
    @DisplayName("明细行数受 recordLimit 限制（夹到 [1,500]）")
    void 明细行数限制() {
        givenSlices(List.of(
                slice("p1", VIN_A, "fushion_aeb", windowDayAt(20), windowDayAt(20)),
                slice("p2", VIN_A, "fushion_aeb", windowDayAt(10), windowDayAt(10)),
                slice("p3", VIN_A, "fushion_aeb", windowDayAt(3), windowDayAt(3))));

        assertThat(service.getWindowPanel(null, null, 2).getWindowRecords()).hasSize(2);
        assertThat(service.getWindowPanel(null, null, 999).getWindowRecords()).hasSize(3);
    }

    @Test
    @DisplayName("无数据：返回空态不抛异常，事件选项仍返回 6 个，窗口仍标明是哪一天")
    void 空态() {
        givenSlices(List.of());

        MxfWindowPanelResponse resp = service.getWindowPanel(null, null, null);

        assertThat(resp.getWindowRecords()).isEmpty();
        assertThat(resp.getWindowStats().getTotalVehicles()).isZero();
        assertThat(resp.getWindowStats().getTotalTriggers()).isZero();
        assertThat(resp.getWindowStats().getProject()).isNull();
        assertThat(resp.getGeneratedAt()).isNotNull();
        // 空态下勾选框不能空，窗口日期也要能展示（前端标题靠它）
        assertThat(resp.getEventOptions()).hasSize(6);
        assertThat(resp.getWindow().getDate()).isNotNull();
    }

    // ============================================================
    // 5. 不越界：面板只查自己需要的数据
    // ============================================================

    @Test
    @DisplayName("右列面板不查 VIN 聚合（车辆下拉只服务左上面板）")
    void 不查VIN聚合() {
        givenSlices(List.of());

        service.getWindowPanel(null, null, null);

        verify(dataSliceMapper, never()).selectMaps(any());
    }

    @Test
    @DisplayName("右列面板不查忽略名单（该数据只服务左下面板）")
    void 不查忽略名单() {
        givenSlices(List.of());

        service.getWindowPanel(null, null, null);

        verify(offlineNoteService, never()).listAll();
    }
}
