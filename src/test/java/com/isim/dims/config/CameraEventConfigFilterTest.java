package com.isim.dims.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CameraEventConfig} 筛选器反查索引测试（**读真实配置文件**）。
 *
 * <p>看板中间列的下拉框按 {@code filter} 展示、查询时映射回 {@code event_name}，
 * 所以这里直接加载 {@code classpath:config/camera_event_config.json}，
 * 用真实配置锁住「一个 filter 对应多个 event」这一事实 —— 哪天配置被改坏
 * （比如 filter 写成了事件名、或某个事件的 filter 被删），本测试会先红。</p>
 *
 * @feature: mxf
 */
class CameraEventConfigFilterTest {

    private CameraEventConfig config;

    @BeforeEach
    void setUp() {
        config = new CameraEventConfig();
        config.load();
    }

    @Test
    @DisplayName("真实配置：50 个事件 → 48 个筛选器（1 个筛选器对应 2 个事件）")
    void 筛选器索引与事件数() {
        Map<String, List<String>> index = config.getFilterEventIndex();

        assertThat(config.getEvents()).hasSize(50);
        assertThat(index).hasSize(48);
        // 每个事件恰好归入一个筛选器
        assertThat(index.values().stream().mapToInt(List::size).sum()).isEqualTo(50);

        assertThat(index.get("highbeam_on_filter"))
                .containsExactly("high_highbeam_on", "mid_highbeam_on");
        // APA 两个事件各自独立成筛选项（apa_simplified_event 不再并入 apa_all_time_event）
        assertThat(index.get("apa_all_time_event"))
                .containsExactly("apa_all_time_event_segment");
        assertThat(index.get("apa_simplified_event"))
                .containsExactly("apa_simplified_event");
        // obf_* 仍留在配置里（cameras: [] = 不转换摄像头），只由 FilterOptionProvider 从下拉剔除
        assertThat(index.get("obf_issue_filter"))
                .containsExactly("obf_metric", "obf_issue");

        // filter 名与事件名不同的单映射（最易被"顺手改成同名"）
        assertThat(index.get("hw_resource_monitor_filter")).containsExactly("system_crash");
    }

    @Test
    @DisplayName("resolveEventsByFilter：精确命中 / 归一化宽松命中 / 未命中返空")
    void 按筛选器反查事件() {
        assertThat(config.resolveEventsByFilter("highbeam_on_filter"))
                .containsExactly("high_highbeam_on", "mid_highbeam_on");
        // SECTION 写法（大写 + 连字符）也能命中
        assertThat(config.resolveEventsByFilter("HIGHBEAM-ON-FILTER"))
                .containsExactly("high_highbeam_on", "mid_highbeam_on");
        // 大小写不同的普通写法
        assertThat(config.resolveEventsByFilter("  fushion_aeb  ")).containsExactly("fushion_aeb");
        assertThat(config.resolveEventsByFilter("not_a_filter")).isEmpty();
        assertThat(config.resolveEventsByFilter(null)).isEmpty();
        assertThat(config.resolveEventsByFilter("   ")).isEmpty();
    }

    @Test
    @DisplayName("resolveEventsByFilter：入参是事件名时按「只检索这一个事件」返回（下拉单事件筛选项直接发事件名）")
    void 事件名按自身反查() {
        // hw_resource_monitor_filter 这一项的选中值就是 system_crash —— 库里落的也是它
        assertThat(config.resolveEventsByFilter("system_crash")).containsExactly("system_crash");
        // 归属多事件筛选器的事件名也能单独检索（比整个筛选器更窄，但同样成立）
        assertThat(config.resolveEventsByFilter("mid_highbeam_on")).containsExactly("mid_highbeam_on");
        assertThat(config.resolveEventsByFilter("not_an_event")).isEmpty();
    }

    @Test
    @DisplayName("筛选器索引不可变，且顺序与配置文件一致（首个 = imu_collision）")
    void 索引不可变且保持配置顺序() {
        assertThat(config.getFilterEventIndex().keySet().iterator().next()).isEqualTo("imu_collision");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> config.getFilterEventIndex().put("x", List.of("y")));
    }
}
