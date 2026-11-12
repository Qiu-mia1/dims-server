package com.isim.dims.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日报「事件类型分布」19 类固定桶的归类规则单测。
 *
 * <p>三条不变量（改了必红，别顺手放宽）：</p>
 * <ol>
 *   <li>顺序固定 19 项 —— 前端按索引取 19 色，改顺序等于改配色；</li>
 *   <li>一条切片只落一个桶 —— 否则 {@code Σ(19 桶) + 未归类 == 总触发} 不成立；</li>
 *   <li>前缀匹配只给 APA —— 否则 {@code dssad_collision} 会吞掉 {@code dssad_collision_locked}。</li>
 * </ol>
 *
 * @feature: mxf
 */
class MxfDailyReportEventsTest {

    /** 19 个桶 key，顺序即条形图顺序 */
    private static final List<String> EXPECTED_KEYS = List.of(
            "imu_collision",
            "system_crash",
            "highbeam_on",
            "fushion_aeb",
            "cp_aes_trig_event",
            "cp_ess_trig_event",
            "apa_all_time_event",
            "apa_simplified_event",
            "meb_brake_event",
            "lpnp_emergency_event",
            "lpnp_takeover",
            "rads_takeover",
            "rma_collision_event",
            "nsp_abnormal_event",
            "general_collision_event",
            "unp_tkov_colli",
            "dssad_collision",
            "dssad_collision_locked",
            "dbw_disabled_takeover");

    @Test
    @DisplayName("固定 19 个桶，顺序不可变")
    void 桶清单_顺序固定() {
        assertThat(MxfDailyReportEvents.keySet()).containsExactlyElementsOf(EXPECTED_KEYS);
    }

    @Test
    @DisplayName("每个桶都有中文标题（含 DSSAD 两条）")
    void 标题_全部有中文() {
        assertThat(MxfDailyReportEvents.keySet())
                .allSatisfy(key -> assertThat(MxfDailyReportEvents.titleOf(key))
                        .as("桶 %s 缺标题", key)
                        .isNotBlank()
                        .isNotEqualTo(key));
        // 原型里 dssad_collision 的展示名
        assertThat(MxfDailyReportEvents.titleOf("dssad_collision")).isEqualTo("DSSAD触发");
        assertThat(MxfDailyReportEvents.titleOf("dssad_collision_locked")).isEqualTo("DSSAD触发(锁定)");
        // 手动远光打点是两个 event_name 的合并展示
        assertThat(MxfDailyReportEvents.titleOf("highbeam_on")).isEqualTo("手动远光打点");
    }

    @Test
    @DisplayName("identity 映射：18 个桶 key 本身就是真实 event_name")
    void classify_identity映射() {
        for (String key : EXPECTED_KEYS) {
            if ("highbeam_on".equals(key)) {
                continue; // 合并桶，没有同名 event_name
            }
            assertThat(MxfDailyReportEvents.classify(key)).as("classify(%s)", key).isEqualTo(key);
            // 大小写与空白不影响归类（库里的值可能带脏空白）
            assertThat(MxfDailyReportEvents.classify("  " + key.toUpperCase() + "  ")).isEqualTo(key);
        }
    }

    @Test
    @DisplayName("手动远光打点：high_highbeam_on 与 mid_highbeam_on 落同一个桶")
    void classify_远光合并桶() {
        assertThat(MxfDailyReportEvents.classify("high_highbeam_on"))
                .isEqualTo(MxfDailyReportEvents.BUCKET_HIGHBEAM);
        assertThat(MxfDailyReportEvents.classify("mid_highbeam_on"))
                .isEqualTo(MxfDailyReportEvents.BUCKET_HIGHBEAM);
        // 兜底：历史数据若落了筛选器名，也要算进去（否则会少统计）
        assertThat(MxfDailyReportEvents.classify("highbeam_on_filter"))
                .isEqualTo(MxfDailyReportEvents.BUCKET_HIGHBEAM);
    }

    @Test
    @DisplayName("APA：裸名与 _segment 后缀同桶，apa_simplified_event 不串桶")
    void classify_apa前缀() {
        assertThat(MxfDailyReportEvents.classify("apa_all_time_event"))
                .isEqualTo(MxfDailyReportEvents.BUCKET_APA);
        assertThat(MxfDailyReportEvents.classify("apa_all_time_event_segment"))
                .isEqualTo(MxfDailyReportEvents.BUCKET_APA);
        assertThat(MxfDailyReportEvents.classify("apa_all_time_event_xxx"))
                .isEqualTo(MxfDailyReportEvents.BUCKET_APA);
        assertThat(MxfDailyReportEvents.classify("apa_simplified_event"))
                .isEqualTo("apa_simplified_event");
    }

    @Test
    @DisplayName("dssad_collision_locked 不能被 dssad_collision 吞掉")
    void classify_dssad不吞桶() {
        assertThat(MxfDailyReportEvents.classify("dssad_collision")).isEqualTo("dssad_collision");
        assertThat(MxfDailyReportEvents.classify("dssad_collision_locked"))
                .isEqualTo("dssad_collision_locked");
    }

    @Test
    @DisplayName("被撤销的拼写 fusion_aeb 不算任何桶（原样落「其他」）")
    void classify_fusion_aeb不归类() {
        assertThat(MxfDailyReportEvents.classify("fusion_aeb")).isNull();
        // 双 u 的才是库值
        assertThat(MxfDailyReportEvents.classify("fushion_aeb")).isEqualTo("fushion_aeb");
    }

    @Test
    @DisplayName("空 / 空白 / 未登记事件 → null（由调用方计入「其他」）")
    void classify_未归类() {
        assertThat(MxfDailyReportEvents.classify(null)).isNull();
        assertThat(MxfDailyReportEvents.classify("")).isNull();
        assertThat(MxfDailyReportEvents.classify("   ")).isNull();
        assertThat(MxfDailyReportEvents.classify("obf_metric")).isNull();
        assertThat(MxfDailyReportEvents.classify("obf_issue")).isNull();
        assertThat(MxfDailyReportEvents.classify("RUNNING-INFO")).isNull();
    }

    @Test
    @DisplayName("keySet 返回不可变副本（挡住外部改桶顺序）")
    void keySet_不可变() {
        List<String> keys = MxfDailyReportEvents.keySet();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> keys.add("hacked"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
