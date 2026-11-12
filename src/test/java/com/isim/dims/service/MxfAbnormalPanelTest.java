package com.isim.dims.service;

import com.isim.dims.dto.MxfAbnormalPanelResponse;
import com.isim.dims.dto.MxfAbnormalPanelResponse.AbnormalVin;
import com.isim.dims.dto.MxfAbnormalPanelResponse.IgnoredVin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 左下面板「数据异常车辆」单测（{@code getAbnormalPanel}）。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/abnormal}，<b>无查询参数</b>：
 * 积压判定对每 VIN 的<b>最新切片</b>做全量比对（{@code created_at − effectiveTime > 阈值}），
 * 不受 12H 窗口与选中车辆影响 —— 加窗口会把「最新切片本就落在窗口外」的真积压车全部漏掉。</p>
 *
 * <p>有效触发时刻口径（需求方 2026-09-18）：{@code trigger_time} 为空取 {@code start_ts}，
 * 上传时间取 {@code created_at}。</p>
 *
 * @feature: mxf
 */
class MxfAbnormalPanelTest extends MxfPanelTestSupport {

    // ============================================================
    // 1. 积压判定
    // ============================================================

    @Test
    @DisplayName("最新切片「上传 − 触发」= 1 天（≤ 阈值 2）→ 不算积压")
    void 积压_未超阈值() {
        LocalDateTime now = now();
        givenVinAggregate(vinRowFull(VIN_A, 1, now.minusHours(1), now.minusHours(25), now.minusHours(25)));

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel();

        assertThat(resp.getAbnormalVehicles()).isEmpty();
        assertThat(resp.getThresholds().getAbnormalCount()).isZero();
        assertThat(resp.getThresholds().getBacklogDelayDays()).isEqualTo(2);
        assertThat(resp.getThresholds().isBacklogExceeded()).isFalse();
    }

    @Test
    @DisplayName("最新切片「上传 − 触发」= 3 天（> 阈值 2）→ 判为积压，backlogDays=3")
    void 积压_超阈值() {
        LocalDateTime now = now();
        LocalDateTime upload = now.minusHours(1);
        givenVinAggregate(vinRowFull(VIN_A, 1, upload, upload.minusDays(3), upload.minusDays(3)));

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel();

        assertThat(resp.getAbnormalVehicles()).hasSize(1);
        AbnormalVin abnormal = resp.getAbnormalVehicles().get(0);
        assertThat(abnormal.getVin()).isEqualTo(VIN_A);
        assertThat(abnormal.getDisplay()).isEqualTo(VIN_A);
        assertThat(abnormal.isResolved()).isTrue();
        assertThat(abnormal.getBacklogDays()).isEqualTo(3L);
        assertThat(abnormal.getLastUploadAt()).isEqualTo(upload);
        assertThat(abnormal.getLastTriggerTime()).isEqualTo(upload.minusDays(3));
        assertThat(resp.getThresholds().getAbnormalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("trigger_time 为空时用 start_ts 判积压（需求方口径）")
    void 积压_用startTs兜底() {
        LocalDateTime now = now();
        LocalDateTime upload = now.minusHours(1);
        LocalDateTime startTs = upload.minusDays(4);
        givenVinAggregate(vinRowFull(VIN_A, 1, upload, null, startTs));

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel();

        assertThat(resp.getAbnormalVehicles()).hasSize(1);
        assertThat(resp.getAbnormalVehicles().get(0).getLastTriggerTime()).isEqualTo(startTs);
        assertThat(resp.getAbnormalVehicles().get(0).getBacklogDays()).isEqualTo(4L);
    }

    @Test
    @DisplayName("只看该车最新切片：老切片延迟很大但最新切片正常 → 不算积压")
    void 积压_只看最新切片() {
        LocalDateTime now = now();
        // 聚合行代表「最新切片」→ 延迟 0
        givenVinAggregate(vinRowFull(VIN_A, 2, now.minusMinutes(30), now.minusMinutes(30), now.minusMinutes(30)));

        assertThat(service.getAbnormalPanel().getAbnormalVehicles()).isEmpty();
    }

    @Test
    @DisplayName("积压判定不受 12H 窗口限制（该方法没有窗口/筛选入参）")
    void 积压_不受窗口限制() {
        LocalDateTime now = now();
        LocalDateTime upload = now.minusHours(1);
        // 触发时刻远在 12H 窗口之外
        givenVinAggregate(vinRowFull(VIN_A, 1, upload, upload.minusDays(5), upload.minusDays(5)));

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel();

        assertThat(resp.getAbnormalVehicles()).hasSize(1);
        assertThat(resp.getAbnormalVehicles().get(0).getBacklogDays()).isEqualTo(5L);
    }

    @Test
    @DisplayName("触发时刻回退到 created_at 时延迟为 0 → 不误报")
    void 积压_兜底后退化为零延迟不误报() {
        LocalDateTime now = now();
        givenVinAggregate(vinRowFull(VIN_A, 1, now.minusDays(30), null, null));

        assertThat(service.getAbnormalPanel().getAbnormalVehicles()).isEmpty();
    }

    @Test
    @DisplayName("积压车辆按延迟天数倒序，超阈值置 backlogExceeded")
    void 积压_排序与阈值提示() {
        LocalDateTime now = now();
        config.setBacklogVehicles(2);
        LocalDateTime upload = now.minusMinutes(10);
        givenVinAggregate(
                vinRowFull(VIN_A, 1, upload, upload.minusDays(3), upload.minusDays(3)),
                vinRowFull(VIN_B, 1, upload, upload.minusDays(9), upload.minusDays(9)),
                vinRowFull(VIN_C, 1, upload, upload.minusDays(5), upload.minusDays(5)));

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel();

        assertThat(resp.getAbnormalVehicles()).extracting(AbnormalVin::getBacklogDays)
                .containsExactly(9L, 5L, 3L);
        assertThat(resp.getThresholds().getAbnormalCount()).isEqualTo(3);
        assertThat(resp.getThresholds().getBacklogVehicles()).isEqualTo(2);
        assertThat(resp.getThresholds().isBacklogExceeded()).isTrue();
    }

    // ============================================================
    // 2. 忽略名单（备注 → 撤销）
    // ============================================================

    @Test
    @DisplayName("已填备注的车辆：不计入异常统计，改列 ignored_vehicles 并带上备注")
    void 忽略名单_备注后不再统计() {
        LocalDateTime now = now();
        LocalDateTime upload = now.minusMinutes(10);
        givenVinAggregate(
                vinRowFull(VIN_A, 1, upload, upload.minusDays(8), upload.minusDays(8)),
                vinRowFull(VIN_B, 1, upload, upload.minusDays(6), upload.minusDays(6)));
        givenIgnored(note(VIN_A, "该车已返厂维修，暂不统计"));

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel();

        assertThat(resp.getAbnormalVehicles()).extracting(AbnormalVin::getVin).containsExactly(VIN_B);
        assertThat(resp.getThresholds().getAbnormalCount()).isEqualTo(1);
        assertThat(resp.getThresholds().getIgnoredCount()).isEqualTo(1);
        assertThat(resp.getIgnoredVehicles()).hasSize(1);
        assertThat(resp.getIgnoredVehicles().get(0).getVin()).isEqualTo(VIN_A);
        assertThat(resp.getIgnoredVehicles().get(0).getDisplay()).isEqualTo(VIN_A);
        assertThat(resp.getIgnoredVehicles().get(0).getNote()).isEqualTo("该车已返厂维修，暂不统计");
        assertThat(resp.getIgnoredVehicles().get(0).getLastUploadAt()).isEqualTo(upload);
    }

    @Test
    @DisplayName("备注车辆即使本身不积压也出现在 ignored_vehicles，展示明文 VIN")
    void 忽略名单_展示明文VIN() {
        LocalDateTime now = now();
        givenVinAggregate(vinRowFull(VIN_A, 1, now.minusMinutes(5), now.minusMinutes(5), now.minusMinutes(5)));
        givenIgnored(note(VIN_A, "测试车"));

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel();

        assertThat(resp.getAbnormalVehicles()).isEmpty();
        assertThat(resp.getIgnoredVehicles()).hasSize(1);
        IgnoredVin ignored = resp.getIgnoredVehicles().get(0);
        assertThat(ignored.isResolved()).isTrue();
        assertThat(ignored.getDisplay()).isEqualTo(VIN_A);
        assertThat(resp.getThresholds().getIgnoredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("备注表查询失败时降级为「无忽略车辆」，不抛异常")
    void 忽略名单_查询失败降级() {
        LocalDateTime now = now();
        org.mockito.Mockito.when(offlineNoteService.listAll())
                .thenThrow(new RuntimeException("relation \"mxf_offline_note\" does not exist"));
        LocalDateTime upload = now.minusMinutes(10);
        givenVinAggregate(vinRowFull(VIN_A, 1, upload, upload.minusDays(4), upload.minusDays(4)));

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel();

        assertThat(resp.getIgnoredVehicles()).isEmpty();
        assertThat(resp.getThresholds().getIgnoredCount()).isZero();
        assertThat(resp.getAbnormalVehicles()).hasSize(1);
    }

    // ============================================================
    // 3. 空态
    // ============================================================

    @Test
    @DisplayName("无数据：返回空态不抛异常")
    void 空态() {
        givenVinAggregate();

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel();

        assertThat(resp.getAbnormalVehicles()).isEmpty();
        assertThat(resp.getIgnoredVehicles()).isEmpty();
        assertThat(resp.getThresholds().getAbnormalCount()).isZero();
        assertThat(resp.getThresholds().getIgnoredCount()).isZero();
        assertThat(resp.getThresholds().isBacklogExceeded()).isFalse();
        assertThat(resp.getGeneratedAt()).isNotNull();
    }

    // ============================================================
    // 4. 不越界：面板只查自己需要的数据
    // ============================================================

    @Test
    @DisplayName("左下面板完全不查切片列表（只走 VIN 聚合 + 备注表）")
    void 左下面板不查切片列表() {
        givenVinAggregate(vinRowFull(VIN_A, 1, now(), now(), now()));

        service.getAbnormalPanel();

        verify(dataSliceMapper, never()).selectDashboardRows(anyInt());
    }

    @Test
    @DisplayName("有已忽略车辆时依然不查切片列表（vin 是明文，无需回查反查表）")
    void 有忽略车辆时也不查切片列表() {
        givenVinAggregate(vinRowFull(VIN_A, 1, now(), now(), now()));
        givenIgnored(note(VIN_A, "返厂"));

        service.getAbnormalPanel();

        verify(dataSliceMapper, never()).selectDashboardRows(anyInt());
    }

    @Test
    @DisplayName("左下面板不读筛选器配置（该数据只服务中上面板）")
    void 不读筛选器配置() {
        givenVinAggregate();

        service.getAbnormalPanel();

        verify(cameraEventConfig, never()).getFilterEventIndex();
    }

    // ============================================================
    // 5. v6：项目下拉联动 + 软件版本列
    // ============================================================

    @Test
    @DisplayName("sourceType 下推为 source_type 条件（项目下拉联动积压卡）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 来源_下推SQL条件() {
        LocalDateTime now = now();
        givenVinAggregate(vinRowFull(VIN_A, 1, now, now, now));

        service.getAbnormalPanel("ZYT");

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(dataSliceMapper).selectMaps(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("source_type");
    }

    @Test
    @DisplayName("无参重载 / sourceType 为空 → 不带 source_type 条件（向后兼容的全量口径）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 来源_为空不过滤() {
        LocalDateTime now = now();
        givenVinAggregate(vinRowFull(VIN_A, 1, now, now, now));

        service.getAbnormalPanel();

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(dataSliceMapper).selectMaps(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("source_type");
    }

    @Test
    @DisplayName("软件版本列：取最新切片的 system_name，缺失为 null（前端兜 '-'）")
    void 软件版本_透出() {
        LocalDateTime now = now();
        LocalDateTime upload = now.minusMinutes(10);
        givenVinAggregate(
                vinRowWithSystem(VIN_A, 1, upload, upload.minusDays(3), upload.minusDays(3), "V2.3.1"),
                vinRowWithSystem(VIN_B, 1, upload, upload.minusDays(4), upload.minusDays(4), null));

        MxfAbnormalPanelResponse resp = service.getAbnormalPanel("MMT");

        assertThat(resp.getAbnormalVehicles())
                .extracting(AbnormalVin::getVin, AbnormalVin::getSystemName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(VIN_B, null),
                        org.assertj.core.groups.Tuple.tuple(VIN_A, "V2.3.1"));
    }
}
