package com.isim.dims.service.impl;

import com.isim.dims.config.CameraEventConfig;
import com.isim.dims.dto.FilterOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link FilterOptionProvider} 单测 —— 锁住「触发类型选项」的组装口径。
 *
 * <p>本类聚焦<b>转换逻辑</b>（顺序、多事件、中文标题、降级），配置索引由 mock 提供；
 * 真实 {@code camera_event_config.json} 的规模与映射由
 * {@code CameraEventConfigFilterTest} 负责看住。两者合起来覆盖
 * 「数据浏览器下拉 == 看板筛选器下拉」这一约束。</p>
 *
 * @feature: slice
 */
@ExtendWith(MockitoExtension.class)
class FilterOptionProviderTest {

    @Mock
    private CameraEventConfig cameraEventConfig;

    private FilterOptionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FilterOptionProvider(cameraEventConfig);
    }

    private void givenIndex(Map<String, List<String>> index) {
        when(cameraEventConfig.getFilterEventIndex()).thenReturn(index);
    }

    @Test
    @DisplayName("按配置顺序输出筛选器，中文标题与事件名同下标一一对应")
    void 顺序与标题映射() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        index.put("imu_collision", List.of("imu_collision"));
        index.put("highbeam_on_filter", List.of("high_highbeam_on", "mid_highbeam_on"));
        index.put("hw_resource_monitor_filter", List.of("system_crash"));
        givenIndex(index);

        List<FilterOption> options = provider.list();

        assertThat(options).extracting(FilterOption::getFilter)
                .containsExactly("imu_collision", "highbeam_on_filter", "hw_resource_monitor_filter");

        // 单事件：titles 长度 1
        assertThat(options.get(0).getEventNames()).containsExactly("imu_collision");
        assertThat(options.get(0).getTitles()).containsExactly("IMU碰撞");

        // 多事件：两个变体各占一项，中文枚举值相同（都是「打点」）
        assertThat(options.get(1).getEventNames())
                .containsExactly("high_highbeam_on", "mid_highbeam_on");
        assertThat(options.get(1).getTitles()).containsExactly("打点", "打点");

        // filter 名与事件名不同的单映射
        assertThat(options.get(2).getEventNames()).containsExactly("system_crash");
        assertThat(options.get(2).getTitles()).containsExactly("系统崩溃");
    }

    @Test
    @DisplayName("未登记中文的事件原样返回事件名（与前端 getEventTitle 同语义）")
    void 未登记中文回退事件名() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        index.put("weird_filter", List.of("alpha_one", "beta_two"));
        givenIndex(index);

        List<FilterOption> options = provider.list();

        assertThat(options).hasSize(1);
        assertThat(options.get(0).getTitles()).containsExactly("alpha_one", "beta_two");
    }

    @Test
    @DisplayName("隐藏筛选器（obf_issue_filter）不进下拉，但配置索引里仍在")
    void 隐藏筛选器不展示() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        index.put("imu_collision", List.of("imu_collision"));
        index.put("obf_issue_filter", List.of("obf_metric", "obf_issue"));
        givenIndex(index);

        List<FilterOption> options = provider.list();

        assertThat(options).extracting(FilterOption::getFilter).containsExactly("imu_collision");
        // 只是不展示：检索侧仍能按该筛选器展开事件（老链接/手工传参不失效）
        assertThat(cameraEventConfig.getFilterEventIndex()).containsKey("obf_issue_filter");
    }

    @Test
    @DisplayName("配置缺失 / 加载失败 → 空列表（调用方显示空态，不抛异常）")
    void 空配置返回空列表() {
        givenIndex(Map.of());
        assertThat(provider.list()).isEmpty();
    }

    @Test
    @DisplayName("索引为 null → 空列表（CameraEventConfig 加载失败时给的就是空 map，这里兜住 null）")
    void 索引为null返回空列表() {
        givenIndex(null);
        assertThat(provider.list()).isEmpty();
    }

    @Test
    @DisplayName("某个筛选器的事件列表为 null → 该项事件与标题均为空列表，不抛 NPE")
    void 事件列表为null不炸() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        index.put("weird_filter", null);
        givenIndex(index);

        List<FilterOption> options = provider.list();

        assertThat(options).hasSize(1);
        assertThat(options.get(0).getFilter()).isEqualTo("weird_filter");
        assertThat(options.get(0).getEventNames()).isEmpty();
        assertThat(options.get(0).getTitles()).isEmpty();
    }

    @Test
    @DisplayName("titles 与 event_names 恒等长（前端按下标配对渲染，长度不一致会串位）")
    void 标题与事件等长() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        index.put("highbeam_on_filter", List.of("high_highbeam_on", "mid_highbeam_on"));
        index.put("weird_filter", List.of("alpha_one", "beta_two"));
        index.put("imu_collision", List.of("imu_collision"));
        givenIndex(index);

        List<FilterOption> options = provider.list();

        assertThat(options).allSatisfy(opt ->
                assertThat(opt.getTitles()).hasSameSizeAs(opt.getEventNames()));
    }

    // ---------------- resolveEventNames：检索侧把「选中筛选器」展开成事件名 ----------------

    @Test
    @DisplayName("1:N 筛选器展开为多个事件名（检索按并集命中，这是「选筛选器」与「选事件」的本质差别）")
    void 展开多事件筛选器() {
        when(cameraEventConfig.resolveEventsByFilter("highbeam_on_filter"))
                .thenReturn(List.of("high_highbeam_on", "mid_highbeam_on"));

        assertThat(provider.resolveEventNames("highbeam_on_filter"))
                .containsExactly("high_highbeam_on", "mid_highbeam_on");
    }

    @Test
    @DisplayName("1:1 筛选器展开为单个事件名（filter 名与事件名可以完全不同）")
    void 展开单事件筛选器() {
        when(cameraEventConfig.resolveEventsByFilter("hw_resource_monitor_filter"))
                .thenReturn(List.of("system_crash"));

        assertThat(provider.resolveEventNames("hw_resource_monitor_filter"))
                .containsExactly("system_crash");
    }

    @Test
    @DisplayName("未命中配置 → 兜底成 [filter] 当事件名去匹配：结果是空集，而不是静默返回全量")
    void 未命中兜底为原值() {
        when(cameraEventConfig.resolveEventsByFilter("no_such_filter")).thenReturn(List.of());

        assertThat(provider.resolveEventNames("no_such_filter")).containsExactly("no_such_filter");
    }

    @Test
    @DisplayName("空白 / null 筛选器 → 空列表（Service 据此判定不展开，等价于不加该条件）")
    void 空白筛选器返回空() {
        assertThat(provider.resolveEventNames(null)).isEmpty();
        assertThat(provider.resolveEventNames("   ")).isEmpty();
    }

    @Test
    @DisplayName("展开前去掉首尾空白（URL 手拼出的空格不该导致匹配失败）")
    void 展开前去除首尾空白() {
        when(cameraEventConfig.resolveEventsByFilter(" imu_collision ")).thenReturn(List.of("imu_collision"));

        assertThat(provider.resolveEventNames(" imu_collision ")).containsExactly("imu_collision");
    }
}
