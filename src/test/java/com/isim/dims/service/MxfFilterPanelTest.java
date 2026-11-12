package com.isim.dims.service;

import com.isim.dims.dto.MxfEventRecord;
import com.isim.dims.dto.MxfFilterPanelResponse;
import com.isim.dims.dto.MxfFilterPanelResponse.FilterOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中上面板「筛选器视角」单测（{@code getFilterPanel}）。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/filter}。下拉展示
 * {@code camera_event_config.json} 的 {@code events.*.filter} 名；一个筛选器可能映射
 * 多个 {@code event_name}（如 {@code highbeam_on_filter} → 高中/中远光），按并集命中。</p>
 *
 * <p>数据源 {@code data_slice}：{@code event_name} 就是配置里的 key，无需二次映射。</p>
 *
 * @feature: mxf
 */
class MxfFilterPanelTest extends MxfPanelTestSupport {

    // ============================================================
    // 1. 筛选器选项与默认选中
    // ============================================================

    @Test
    @DisplayName("筛选器选项：保持配置顺序、按 filter 去重；一个 filter 可映射多个事件")
    void 筛选器选项() {
        givenSlices(List.of());
        givenVinAggregate();

        MxfFilterPanelResponse resp = service.getFilterPanel(null, null, null, null);

        assertThat(resp.getFilterOptions()).extracting(FilterOption::getFilter)
                .containsExactly("imu_collision", "hw_resource_monitor_filter", FILTER_MULTI,
                        "fushion_aeb", "cp_aes_trig_event", "cp_ess_trig_event",
                        "unp_tkov_colli", "dssad_collision", "lpnp_takeover", "rads_takeover");
        FilterOption multi = resp.getFilterOptions().stream()
                .filter(o -> FILTER_MULTI.equals(o.getFilter()))
                .findFirst().orElseThrow();
        assertThat(multi.getEventNames()).containsExactlyElementsOf(FILTER_MULTI_EVENTS);
        // 两个变体登记的是同一个中文枚举值（都是「打点」），不去重—— 与 event_names 同下标配对
        assertThat(multi.getTitles()).containsExactly("打点", "打点");
    }

    @Test
    @DisplayName("未传参 → 默认第一个筛选器；集合唯一时 selected_event 才给值")
    void 默认选中第一个筛选器() {
        givenSlices(List.of());
        givenVinAggregate();

        MxfFilterPanelResponse resp = service.getFilterPanel(null, null, null, null);

        assertThat(resp.getSelectedFilter()).isEqualTo("imu_collision");
        assertThat(resp.getSelectedFilterEvents()).containsExactly("imu_collision");
        assertThat(resp.getSelectedEvent()).isEqualTo("imu_collision");
    }

    @Test
    @DisplayName("筛选器映射多个事件时，单值兼容字段 selected_event 置空")
    void 多事件映射时单值字段置空() {
        givenSlices(List.of());
        givenVinAggregate();

        MxfFilterPanelResponse resp = service.getFilterPanel(FILTER_MULTI, null, null, null);

        assertThat(resp.getSelectedFilter()).isEqualTo(FILTER_MULTI);
        assertThat(resp.getSelectedFilterEvents()).containsExactlyElementsOf(FILTER_MULTI_EVENTS);
        assertThat(resp.getSelectedEvent()).isNull();
    }

    @Test
    @DisplayName("filter 直接传事件名（下拉里 hw_resource_monitor_filter 这一项取得就是 system_crash）→ 只按该事件过滤并原样回显")
    void filter传事件名() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", VIN_A, "system_crash", now.minusDays(1), now.minusDays(1)),
                // 同数据集内的其它事件不应被带出来
                slice("p2", VIN_B, "imu_collision", now.minusDays(1), now.minusDays(1))));
        givenVinAggregate(vinRow(VIN_A, 1, now.minusDays(1)));

        MxfFilterPanelResponse resp = service.getFilterPanel("system_crash", null, null, null);

        assertThat(resp.getSelectedFilter()).isEqualTo("system_crash");
        assertThat(resp.getSelectedFilterEvents()).containsExactly("system_crash");
        assertThat(resp.getSelectedEvent()).isEqualTo("system_crash");
        assertThat(resp.getEventRecords()).extracting(MxfEventRecord::getEventName)
                .containsExactly("system_crash");
    }

    // ============================================================
    // 2. 按筛选器查询
    // ============================================================

    @Test
    @DisplayName("按筛选器查询：命中该 filter 映射的全部事件（并集），行内仍展示真实 event_name")
    void 按筛选器查询命中多个事件() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", VIN_A, "high_highbeam_on", now.minusDays(1), now.minusDays(1)),
                slice("p2", VIN_B, "mid_highbeam_on", now.minusDays(1), now.minusDays(1)),
                slice("p3", VIN_C, "high_highbeam_on", now.minusDays(2), now.minusDays(2)),
                // 不在该筛选器下 → 不应出现
                slice("p4", VIN_C, "imu_collision", now.minusDays(1), now.minusDays(1))));
        givenVinAggregate(
                vinRow(VIN_A, 1, now.minusDays(1)),
                vinRow(VIN_B, 1, now.minusDays(1)),
                vinRow(VIN_C, 2, now.minusDays(1)));

        MxfFilterPanelResponse resp = service.getFilterPanel(FILTER_MULTI, null, 7, null);

        assertThat(resp.getEventRecords()).hasSize(3);
        assertThat(resp.getEventRecords()).extracting(MxfEventRecord::getEventName)
                .containsExactlyInAnyOrder("high_highbeam_on", "mid_highbeam_on", "high_highbeam_on");
        // 趋势按切片数：今天 0、昨天 2、前天 1
        assertThat(resp.getEventTrend()).hasSize(7);
        assertThat(resp.getEventTrend().get(4).getCount()).isEqualTo(1L);
        assertThat(resp.getEventTrend().get(5).getCount()).isEqualTo(2L);
        assertThat(resp.getEventTrend().get(6).getCount()).isZero();
    }

    @Test
    @DisplayName("明细按有效触发时刻倒序；VIN 为明文直接展示")
    void 明细倒序且带明文VIN() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("old", VIN_A, "fushion_aeb", now.minusHours(1), now.minusHours(9)),
                slice("new", VIN_A, "fushion_aeb", now.minusMinutes(30), now.minusMinutes(30))));
        givenVinAggregate(vinRow(VIN_A, 2, now.minusMinutes(30)));

        MxfFilterPanelResponse resp = service.getFilterPanel("fushion_aeb", null, null, null);

        assertThat(resp.getEventRecords()).extracting(MxfEventRecord::getSliceId)
                .containsExactly("new", "old");
        assertThat(resp.getEventRecords().get(0).getVinDisplay()).isEqualTo(VIN_A);
        assertThat(resp.getEventRecords().get(0).isVinResolved()).isTrue();
    }

    @Test
    @DisplayName("VIN 为 null 的切片：vin_display 为空串、标记未识别（前端展示「-」）")
    void 空VIN切片标记未识别() {
        LocalDateTime now = now();
        givenSlices(List.of(slice("p1", null, "fushion_aeb", now.minusHours(1), now.minusHours(1))));

        MxfFilterPanelResponse resp = service.getFilterPanel("fushion_aeb", null, null, null);

        assertThat(resp.getEventRecords()).hasSize(1);
        assertThat(resp.getEventRecords().get(0).getVin()).isNull();
        assertThat(resp.getEventRecords().get(0).getVinDisplay()).isEmpty();
        assertThat(resp.getEventRecords().get(0).isVinResolved()).isFalse();
    }

    // ============================================================
    // 3. 传参校验与降级
    // ============================================================

    @Test
    @DisplayName("未知 filter 回退第一个筛选器；同时传 filter 与 event 时 filter 优先")
    void 筛选器传参校验() {
        LocalDateTime now = now();
        givenSlices(List.of(slice("p1", VIN_A, "imu_collision", now.minusDays(1), now.minusDays(1))));
        givenVinAggregate(vinRow(VIN_A, 1, now.minusDays(1)));

        MxfFilterPanelResponse unknown = service.getFilterPanel("not_a_filter", null, null, null);
        assertThat(unknown.getSelectedFilter()).isEqualTo("imu_collision");
        assertThat(unknown.getSelectedFilterEvents()).containsExactly("imu_collision");

        MxfFilterPanelResponse precedence = service.getFilterPanel(FILTER_MULTI, "fushion_aeb", null, null);
        assertThat(precedence.getSelectedFilter()).isEqualTo(FILTER_MULTI);
        assertThat(precedence.getSelectedFilterEvents()).containsExactlyElementsOf(FILTER_MULTI_EVENTS);
    }

    @Test
    @DisplayName("旧 event 参数兼容：筛选器回填其所属 filter，但只过滤该单个事件")
    void event参数向后兼容() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", VIN_A, "high_highbeam_on", now.minusDays(1), now.minusDays(1)),
                slice("p2", VIN_B, "mid_highbeam_on", now.minusDays(1), now.minusDays(1))));
        givenVinAggregate(
                vinRow(VIN_A, 1, now.minusDays(1)),
                vinRow(VIN_B, 1, now.minusDays(1)));

        MxfFilterPanelResponse resp = service.getFilterPanel(null, "mid_highbeam_on", null, null);

        assertThat(resp.getSelectedFilter()).isEqualTo(FILTER_MULTI);
        // 不把同 filter 下的 high_highbeam_on 一起捞出来
        assertThat(resp.getSelectedFilterEvents()).containsExactly("mid_highbeam_on");
        assertThat(resp.getSelectedEvent()).isEqualTo("mid_highbeam_on");
        assertThat(resp.getEventRecords()).extracting(MxfEventRecord::getEventName)
                .containsExactly("mid_highbeam_on");
    }

    @Test
    @DisplayName("筛选器配置缺失时降级为按事件名过滤（旧行为）")
    void 无筛选器配置时降级() {
        when(cameraEventConfig.getFilterEventIndex()).thenReturn(Collections.emptyMap());
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", VIN_A, "fushion_aeb", now.minusDays(1), now.minusDays(1)),
                slice("p2", VIN_A, "imu_collision", now.minusDays(1), now.minusDays(1))));
        givenVinAggregate(vinRow(VIN_A, 2, now.minusDays(1)));

        MxfFilterPanelResponse resp = service.getFilterPanel(null, "fushion_aeb", null, null);

        assertThat(resp.getFilterOptions()).isEmpty();
        assertThat(resp.getSelectedFilter()).isEqualTo("fushion_aeb");
        assertThat(resp.getSelectedFilterEvents()).containsExactly("fushion_aeb");
        assertThat(resp.getEventRecords()).hasSize(1);
    }

    @Test
    @DisplayName("筛选器配置缺失且未传 event → 用配置默认勾选的第一个事件兜底")
    void 无筛选器配置且未传参时兜底() {
        when(cameraEventConfig.getFilterEventIndex()).thenReturn(Collections.emptyMap());
        givenSlices(List.of());
        givenVinAggregate();

        MxfFilterPanelResponse resp = service.getFilterPanel(null, null, null, null);

        assertThat(resp.getFilterOptions()).isEmpty();
        assertThat(resp.getSelectedFilter()).isEqualTo("imu_collision");
        assertThat(resp.getSelectedFilterEvents()).containsExactly("imu_collision");
    }

    // ============================================================
    // 4. 行数限制 / 空态
    // ============================================================

    @Test
    @DisplayName("明细行数受 recordLimit 限制（夹到 [1,500]）")
    void 明细行数限制() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", VIN_A, "fushion_aeb", now.minusHours(1), now.minusHours(1)),
                slice("p2", VIN_A, "fushion_aeb", now.minusHours(2), now.minusHours(2)),
                slice("p3", VIN_A, "fushion_aeb", now.minusHours(3), now.minusHours(3))));
        givenVinAggregate(vinRowFull(VIN_A, 3, now.minusHours(1), now.minusHours(1), now.minusHours(1)));

        assertThat(service.getFilterPanel("fushion_aeb", null, null, 2).getEventRecords()).hasSize(2);
        assertThat(service.getFilterPanel("fushion_aeb", null, null, 2)
                .getEventRecords().get(0).getSliceId()).isEqualTo("p1");
        assertThat(service.getFilterPanel("fushion_aeb", null, null, 999).getEventRecords()).hasSize(3);
    }

    @Test
    @DisplayName("无数据：返回空态不抛异常")
    void 空态() {
        givenSlices(List.of());
        givenVinAggregate();

        MxfFilterPanelResponse resp = service.getFilterPanel(null, null, null, null);

        assertThat(resp.getEventRecords()).isEmpty();
        // 未传 trendDays → 用配置默认值（需求口径 3 天）
        assertThat(resp.getEventTrend()).hasSize(config.getTrendDays()).hasSize(3);
        assertThat(resp.getGeneratedAt()).isNotNull();
        // 空态下筛选器选项仍要返回（前端下拉不能空）
        assertThat(resp.getFilterOptions()).isNotEmpty();
    }

    // ============================================================
    // 5. 不越界：面板只查自己需要的数据
    // ============================================================

    @Test
    @DisplayName("中上面板不查 VIN 聚合（车辆下拉只服务左上面板）")
    void 不查VIN聚合() {
        givenSlices(List.of(slice("p1", VIN_A, "fushion_aeb", now(), now())));

        service.getFilterPanel("fushion_aeb", null, null, null);

        verify(dataSliceMapper, never()).selectMaps(any());
    }

    @Test
    @DisplayName("中上面板不查忽略名单（该数据只服务左下面板）")
    void 不查忽略名单() {
        givenSlices(List.of());

        service.getFilterPanel(null, null, null, null);

        verify(offlineNoteService, never()).listAll();
    }
}
