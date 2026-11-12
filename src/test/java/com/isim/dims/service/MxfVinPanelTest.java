package com.isim.dims.service;

import com.isim.dims.dto.MxfVinPanelResponse;
import com.isim.dims.dto.MxfVinPanelResponse.VinRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 左上面板「单车视角」单测（{@code getVinPanel}）。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/vin}：车辆下拉 + 选中车辆 + 该车按天趋势 +
 * 该车切片级明细。不受筛选器 / 重点事件 / 积压判定影响。</p>
 *
 * <p>数据源 {@code data_slice}：{@code vin} 是明文 VIN，不再做 md5 反查。</p>
 *
 * @feature: mxf
 */
class MxfVinPanelTest extends MxfPanelTestSupport {

    // ============================================================
    // 1. 默认选中 / VIN 展示
    // ============================================================

    @Test
    @DisplayName("默认无参：选中最后上传时间最新的车辆")
    void 默认选中最近上传的VIN() {
        LocalDateTime now = now();
        givenSlices(List.of(slice("p1", VIN_A, null, now.minusDays(4), now.minusDays(4))));
        givenVinAggregate(
                vinRow(VIN_A, 1, now.minusDays(4)),
                vinRow(VIN_B, 1, now.minusHours(1)));

        MxfVinPanelResponse resp = service.getVinPanel(null, null, null);

        assertThat(resp.getSelectedVin()).isNotNull();
        assertThat(resp.getSelectedVin().getVin()).isEqualTo(VIN_B);
        assertThat(resp.getVinOptions()).hasSize(2);
    }

    @Test
    @DisplayName("显式指定的 VIN 命中列表时按它选中（不做「最近上传」覆盖）")
    void 显式VIN优先() {
        LocalDateTime now = now();
        givenSlices(List.of(slice("p1", VIN_A, null, now.minusDays(4), now.minusDays(4))));
        givenVinAggregate(
                vinRow(VIN_A, 1, now.minusDays(4)),
                vinRow(VIN_B, 1, now.minusHours(1)));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_A, null, null);

        assertThat(resp.getSelectedVin().getVin()).isEqualTo(VIN_A);
    }

    @Test
    @DisplayName("明文 VIN：下拉直接展示原值并标记已识别（换源后不再有 md5 反查）")
    void 明文VIN直接展示() {
        LocalDateTime now = now();
        givenSlices(List.of(slice("p1", VIN_A, "fushion_aeb", now.minusHours(1), now.minusHours(1))));
        givenVinAggregate(vinRow(VIN_A, 1, now.minusHours(1)));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_A, null, null);

        assertThat(resp.getSelectedVin().isResolved()).isTrue();
        assertThat(resp.getSelectedVin().getDisplay()).isEqualTo(VIN_A);
    }

    @Test
    @DisplayName("VIN 为 null 的切片不进车辆下拉（无法归属车辆，只在明细里以「-」出现）")
    void 空VIN不进下拉() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", null, "fushion_aeb", now.minusHours(1), now.minusHours(1)),
                slice("p2", VIN_A, "fushion_aeb", now.minusHours(2), now.minusHours(2))));
        // 聚合 SQL 已过滤空 VIN，这里只回 VIN_A 一行
        givenVinAggregate(vinRow(VIN_A, 1, now.minusHours(2)));

        MxfVinPanelResponse resp = service.getVinPanel(null, null, null);

        assertThat(resp.getVinOptions()).hasSize(1);
        assertThat(resp.getSelectedVin().getVin()).isEqualTo(VIN_A);
    }

    // ============================================================
    // 2. 明细：切片级单事件
    // ============================================================

    @Test
    @DisplayName("明细行用单值 event_name / event_title，并带上切片 ID 与名称")
    void VIN明细为单事件() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", VIN_A, "unp_tkov_colli", now.minusHours(1), now.minusHours(1)),
                slice("p2", VIN_A, null, now.minusHours(2), now.minusHours(2))));
        givenVinAggregate(vinRow(VIN_A, 2, now.minusHours(1)));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_A, null, null);

        assertThat(resp.getVinRecords()).hasSize(2);
        assertThat(resp.getVinRecords().get(0).getSliceId()).isEqualTo("p1");
        assertThat(resp.getVinRecords().get(0).getEventName()).isEqualTo("unp_tkov_colli");
        // 标题取 MxfSpecialEvents.EVENT_TITLES 的当前文案（unp_tkov_colli = UNP碰撞）；
        // 原断言写的是旧文案「接管碰撞」，在标题统一改名后一直红，此处同步
        assertThat(resp.getVinRecords().get(0).getEventTitle()).isEqualTo("UNP碰撞");
        assertThat(resp.getVinRecords().get(0).getFileName()).contains(VIN_A);
        assertThat(resp.getVinRecords().get(1).getEventName()).isNull();
        assertThat(resp.getVinRecords().get(1).getEventTitle()).isNull();
    }

    @Test
    @DisplayName("只返回选中车辆自己的切片")
    void 只返回选中车辆的切片() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", VIN_A, "fushion_aeb", now.minusHours(1), now.minusHours(1)),
                slice("p2", VIN_B, "fushion_aeb", now.minusHours(2), now.minusHours(2))));
        givenVinAggregate(
                vinRow(VIN_A, 1, now.minusHours(1)),
                vinRow(VIN_B, 1, now.minusHours(2)));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_B, null, null);

        assertThat(resp.getVinRecords()).extracting(VinRecord::getSliceId).containsExactly("p2");
    }

    @Test
    @DisplayName("归档切片（minio 模式 + storage_type=LOCAL）标记为不可下载，MINIO 切片可下载")
    void 归档切片不可下载() {
        LocalDateTime now = now();
        givenSlices(List.of(
                archived(slice("p1", VIN_A, "fushion_aeb", now.minusHours(1), now.minusHours(1))),
                slice("p2", VIN_A, "fushion_aeb", now.minusHours(2), now.minusHours(2))));
        givenVinAggregate(vinRow(VIN_A, 2, now.minusHours(1)));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_A, null, null);

        assertThat(resp.getVinRecords().get(0).isDownloadable()).isFalse();
        assertThat(resp.getVinRecords().get(1).isDownloadable()).isTrue();
    }

    // ============================================================
    // 3. 触发时刻三级兜底
    // ============================================================

    @Test
    @DisplayName("trigger_time 合法 → 直接用 trigger_time")
    void 触发时刻优先triggerTime() {
        LocalDateTime now = now();
        LocalDateTime trigger = now.minusHours(1);
        givenSlices(List.of(sliceFull("p1", VIN_A, "fushion_aeb",
                now.minusMinutes(10), trigger, now.minusHours(9))));
        givenVinAggregate(vinRowFull(VIN_A, 1, now.minusMinutes(10), trigger, now.minusHours(9)));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_A, null, null);

        assertThat(resp.getVinRecords().get(0).getTime()).isEqualTo(trigger);
    }

    @Test
    @DisplayName("trigger_time 为空 → 取 start_ts（需求方 2026-09-18 口径；实测 14/15 走这条）")
    void 触发时刻为空取startTs() {
        LocalDateTime now = now();
        LocalDateTime startTs = now.minusHours(3);
        givenSlices(List.of(sliceFull("p1", VIN_A, "fushion_aeb",
                now.minusMinutes(5), null, startTs)));
        givenVinAggregate(vinRowFull(VIN_A, 1, now.minusMinutes(5), null, startTs));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_A, null, null);

        assertThat(resp.getVinRecords().get(0).getTime()).isEqualTo(startTs);
    }

    @Test
    @DisplayName("trigger_time 为 1970 坏值 → 回退 start_ts（防上游脏数据）")
    void 触发时刻回退startTs() {
        LocalDateTime now = now();
        LocalDateTime startTs = now.minusHours(3);
        givenSlices(List.of(sliceFull("p1", VIN_A, "fushion_aeb", now.minusMinutes(5),
                LocalDateTime.of(1970, 1, 1, 0, 0, 54), startTs)));
        givenVinAggregate(vinRowFull(VIN_A, 1, now.minusMinutes(5),
                LocalDateTime.of(1970, 1, 1, 0, 0, 54), startTs));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_A, null, null);

        assertThat(resp.getVinRecords().get(0).getTime()).isEqualTo(startTs);
    }

    @Test
    @DisplayName("trigger_time 与 start_ts 都缺失 → 回退 created_at")
    void 触发时刻回退createdAt() {
        LocalDateTime now = now();
        givenSlices(List.of(sliceFull("p1", VIN_A, "fushion_aeb", now.minusMinutes(5), null, null)));
        givenVinAggregate(vinRowFull(VIN_A, 1, now.minusMinutes(5), null, null));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_A, null, null);

        assertThat(resp.getVinRecords().get(0).getTime()).isEqualTo(now.minusMinutes(5));
    }

    // ============================================================
    // 4. 趋势 / 行数限制 / 空态
    // ============================================================

    @Test
    @DisplayName("趋势按天补零：VIN 曲线口径为切片数（同一片算 1 次）")
    void 趋势按天补零() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", VIN_A, "fushion_aeb", now.minusDays(1), now.minusDays(1)),
                slice("p2", VIN_A, "fushion_aeb", now.minusDays(1).minusHours(2),
                        now.minusDays(1).minusHours(2))));
        givenVinAggregate(vinRow(VIN_A, 2, now.minusDays(1)));

        MxfVinPanelResponse resp = service.getVinPanel(VIN_A, 7, null);

        assertThat(resp.getVinTrend()).hasSize(7);
        assertThat(resp.getTrendDays()).isEqualTo(7);
        assertThat(resp.getVinTrend().get(6).getCount()).isZero();
        assertThat(resp.getVinTrend().get(5).getCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("趋势天数受 [1,90] 夹取，未传则用配置默认值")
    void 趋势天数夹取() {
        givenSlices(List.of());
        givenVinAggregate();

        assertThat(service.getVinPanel(null, 999, null).getTrendDays()).isEqualTo(90);
        assertThat(service.getVinPanel(null, 0, null).getTrendDays()).isEqualTo(1);
        assertThat(service.getVinPanel(null, null, null).getTrendDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("明细行数受 recordLimit 限制（夹到 [1,500]）")
    void 明细行数限制() {
        LocalDateTime now = now();
        givenSlices(List.of(
                slice("p1", VIN_A, "fushion_aeb", now.minusHours(1), now.minusHours(1)),
                slice("p2", VIN_A, "fushion_aeb", now.minusHours(2), now.minusHours(2)),
                slice("p3", VIN_A, "fushion_aeb", now.minusHours(3), now.minusHours(3))));
        givenVinAggregate(vinRowFull(VIN_A, 3, now.minusHours(1), now.minusHours(1), now.minusHours(1)));

        assertThat(service.getVinPanel(VIN_A, null, 2).getVinRecords()).hasSize(2);
        assertThat(service.getVinPanel(VIN_A, null, 2).getVinRecords().get(0).getSliceId())
                .isEqualTo("p1");
        assertThat(service.getVinPanel(VIN_A, null, 999).getVinRecords()).hasSize(3);
    }

    @Test
    @DisplayName("无数据：返回空态不抛异常，趋势仍给定长序列")
    void 空态() {
        givenSlices(List.of());
        givenVinAggregate();

        MxfVinPanelResponse resp = service.getVinPanel(null, null, null);

        assertThat(resp.getSelectedVin()).isNull();
        assertThat(resp.getVinOptions()).isEmpty();
        assertThat(resp.getVinRecords()).isEmpty();
        // 未传 trendDays → 用配置默认值（需求口径 3 天），空态也要补齐定长序列
        assertThat(resp.getVinTrend()).hasSize(config.getTrendDays()).hasSize(3);
        assertThat(resp.getGeneratedAt()).isNotNull();
    }

    // ============================================================
    // 5. 不越界：面板只查自己需要的数据
    // ============================================================

    @Test
    @DisplayName("左上面板不查忽略名单（该数据只服务左下面板）")
    void 不查忽略名单() {
        givenSlices(List.of());
        givenVinAggregate();

        service.getVinPanel(null, null, null);

        verify(offlineNoteService, never()).listAll();
    }
}
