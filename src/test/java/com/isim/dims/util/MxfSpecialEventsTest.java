package com.isim.dims.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MXF 8 类特殊事件识别测试。
 *
 * <p>覆盖两件事：</p>
 * <ol>
 *   <li>AEB 事件名以平台侧 {@code camera_event_config.json} 的原始 key 为准 = {@code fushion_aeb}
 *       （拼写不是 f-u-s-i-o-n；2026-09-17 曾改名 {@code fusion_aeb}，09-18 需求方复核后撤销）；</li>
 *   <li>单车看板的 6 个固定重点事件必须全部落在这 8 类里 —— 否则 UI 上勾了也统计不到。</li>
 * </ol>
 *
 * @feature: mxf
 */
class MxfSpecialEventsTest {

    /** 8 类特殊事件（AEB 名以平台 key 为准） */
    private static final List<String> ALL_EVENTS = List.of(
            "dssad_collision",
            "dssad_collision_locked",
            "imu_collision",
            "unp_tkov_colli",
            "fushion_aeb",
            "cp_aes_trig_event",
            "cp_ess_trig_event",
            "system_crash");

    /** 单车看板「过去 12H 重点事件统计」的 6 个固定勾选项 */
    private static final List<String> DASHBOARD_EVENTS = List.of(
            "imu_collision",
            "system_crash",
            "cp_aes_trig_event",
            "cp_ess_trig_event",
            "unp_tkov_colli",
            "fushion_aeb");

    @Test
    @DisplayName("8 类特殊事件名：AEB 为 fushion_aeb（平台原始拼写），不含被撤销的 fusion_aeb")
    void eventNames_拼写口径() {
        Set<String> names = MxfSpecialEvents.eventNames();

        // 不写 containsExactlyInAnyOrder：EVENT_TITLES 还承载 L2/NOA/Park 的业务事件标题
        // （dbccf64 起 48 条），用「包含 8 类」锁定口径即可，免得每次补标题都改这条断言
        assertThat(names).containsAll(ALL_EVENTS);
        assertThat(names).doesNotContain("fusion_aeb");
    }

    @Test
    @DisplayName("看板的 6 个重点事件全部落在 8 类特殊事件内")
    void dashboardEvents_都是特殊事件() {
        assertThat(MxfSpecialEvents.eventNames()).containsAll(DASHBOARD_EVENTS);
    }

    @Test
    @DisplayName("AEB-ACTIVATE 及其带数字后缀的变体都解析为 fushion_aeb")
    void resolveEvent_aeb别名() {
        assertThat(MxfSpecialEvents.resolveEvent("AEB-ACTIVATE")).isEqualTo("fushion_aeb");
        assertThat(MxfSpecialEvents.resolveEvent("aeb.activate.2")).isEqualTo("fushion_aeb");
        assertThat(MxfSpecialEvents.resolveEvent("aeb_activate")).isEqualTo("fushion_aeb");
        // SECTION 名与事件名同名时直接命中（平台若直接落 FUSHION-AEB）
        assertThat(MxfSpecialEvents.resolveEvent("FUSHION-AEB")).isEqualTo("fushion_aeb");
    }

    @Test
    @DisplayName("AES/ESS 别名解析")
    void resolveEvent_aesEss别名() {
        assertThat(MxfSpecialEvents.resolveEvent("AES-ACTIVATE")).isEqualTo("cp_aes_trig_event");
        assertThat(MxfSpecialEvents.resolveEvent("ESS-ACTIVATE")).isEqualTo("cp_ess_trig_event");
    }

    @Test
    @DisplayName("名称本身就是事件名的 SECTION（含分隔符变体）直接命中")
    void resolveEvent_同名section() {
        assertThat(MxfSpecialEvents.resolveEvent("UNP-TKOV-COLLI")).isEqualTo("unp_tkov_colli");
        assertThat(MxfSpecialEvents.resolveEvent("unp_tkov_colli")).isEqualTo("unp_tkov_colli");
        assertThat(MxfSpecialEvents.resolveEvent("SYSTEM.CRASH")).isEqualTo("system_crash");
        assertThat(MxfSpecialEvents.resolveEvent("IMU-COLLISION")).isEqualTo("imu_collision");
        assertThat(MxfSpecialEvents.resolveEvent("DSSAD-COLLISION")).isEqualTo("dssad_collision");
    }

    @Test
    @DisplayName("非特殊事件的 SECTION 返回 null")
    void resolveEvent_普通section() {
        assertThat(MxfSpecialEvents.resolveEvent("RUNNING-INFO")).isNull();
        assertThat(MxfSpecialEvents.resolveEvent("MD-NODE-STATUS-INFO-END")).isNull();
        assertThat(MxfSpecialEvents.resolveEvent("")).isNull();
        assertThat(MxfSpecialEvents.resolveEvent(null)).isNull();
    }

    @Test
    @DisplayName("展示标题取「枚举」中文值；被撤销的 fusion_aeb 只当未知事件原样返回")
    void titleOf_事件名() {
        assertThat(MxfSpecialEvents.titleOf("fushion_aeb")).isEqualTo("AEB触发风险");
        assertThat(MxfSpecialEvents.titleOf("apa_all_time_event_segment")).isEqualTo("APA全量");
        assertThat(MxfSpecialEvents.titleOf("unp_tkov_colli")).isEqualTo("UNP碰撞");
        assertThat(MxfSpecialEvents.titleOf("cp_aes_trig_event")).isEqualTo("AES触发");
        assertThat(MxfSpecialEvents.titleOf("cp_ess_trig_event")).isEqualTo("ESS触发");
        assertThat(MxfSpecialEvents.titleOf("system_crash")).isEqualTo("系统崩溃");
        assertThat(MxfSpecialEvents.titleOf("imu_collision")).isEqualTo("IMU碰撞");
        // DSSAD 两条：0911 定义里没有对应表，但日报 19 类需要中文，已在 EVENT_TITLES 补登记
        assertThat(MxfSpecialEvents.titleOf("dssad_collision")).isEqualTo("DSSAD触发");
        assertThat(MxfSpecialEvents.titleOf("dssad_collision_locked")).isEqualTo("DSSAD触发(锁定)");
        // 被撤销的拼写已不是已知事件 → 原样返回，以此固化「不要再改名」的口径
        assertThat(MxfSpecialEvents.titleOf("fusion_aeb")).isEqualTo("fusion_aeb");
    }

    @Test
    @DisplayName("归一化：非字母数字统一成 _，末尾 -数字 剥离")
    void normalizeSection_规则() {
        assertThat(MxfSpecialEvents.normalizeSection("AEB-ACTIVATE-2")).isEqualTo("aeb_activate");
        assertThat(MxfSpecialEvents.normalizeSection("  dssad.collision  ")).isEqualTo("dssad_collision");
        assertThat(MxfSpecialEvents.normalizeSection("123")).isEqualTo("123");
        assertThat(MxfSpecialEvents.normalizeSection("---")).isNull();
    }
}
