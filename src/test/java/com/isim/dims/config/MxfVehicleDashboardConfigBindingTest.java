package com.isim.dims.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dims.mxf.vehicle.*} 配置绑定验证。
 *
 * <p>Spring Boot 默认**不会**因为「yml 里写了 Java 侧不存在的 key」而启动失败（unknown property
 * 是静默忽略的），所以配置项打错字（如 {@code backlog-delay-day}）只会在运行时表现为「阈值始终是默认值」。
 * 本测试直接用 Spring 自己的 {@link YamlPropertySourceLoader} + {@link Binder} 读取**真实的**
 * {@code application.yml}，逐字段断言，确保全部 key 生效。</p>
 *
 * <p>v2 起 `offline-days` 已被 `backlog-delay-days` 替换，并新增 6 个固定重点事件配置。</p>
 *
 * @feature: mxf
 */
class MxfVehicleDashboardConfigBindingTest {

    /** 2026-10 原型定案的 8 个固定重点事件（顺序即 UI 顺序） */
    private static final List<String> EXPECTED_EVENTS = List.of(
            "imu_collision",
            "system_crash",
            "fushion_aeb",
            "cp_aes_trig_event",
            "cp_ess_trig_event",
            "meb_brake_event",
            "unp_tkov_colli",
            "dssad_collision");

    private MxfVehicleDashboardConfig bindFromRealYaml() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("dims.mxf.vehicle", Bindable.of(MxfVehicleDashboardConfig.class))
                .orElseThrow(() -> new AssertionError("application.yml 里缺少 dims.mxf.vehicle 配置块"));
    }

    @Test
    @DisplayName("application.yml 的全部键均绑定成功（含积压阈值与 8 个重点事件）")
    void applicationYml_全部键均绑定到配置类() throws Exception {
        MxfVehicleDashboardConfig config = bindFromRealYaml();

        assertThat(config.getBacklogDelayDays()).isEqualTo(2);
        assertThat(config.getBacklogVehicles()).isEqualTo(20);
        // v3：右列统计口径从「过去 N 小时」改成「指定日期全天」，配的自然是日期偏移
        assertThat(config.getDefaultDateOffsetDays()).isEqualTo(1);
        assertThat(config.getTrendDays()).isEqualTo(3);
        assertThat(config.getMaxPackets()).isEqualTo(5000);
        assertThat(config.getMaxRecords()).isEqualTo(200);
        assertThat(config.getEventOptions()).containsExactlyElementsOf(EXPECTED_EVENTS);
        assertThat(config.getDefaultEvents()).containsExactlyElementsOf(EXPECTED_EVENTS);
    }

    @Test
    @DisplayName("6 个重点事件全部属于 8 类特殊事件（否则 UI 勾了也统计不到）")
    void 重点事件都在特殊事件集合内() throws Exception {
        assertThat(com.isim.dims.util.MxfSpecialEvents.eventNames())
                .containsAll(bindFromRealYaml().getEventOptions());
    }

    @Test
    @DisplayName("8 个重点事件全部有中文标题（否则 UI 会显示英文 event_name）")
    void 重点事件都有中文标题() throws Exception {
        for (String name : bindFromRealYaml().getEventOptions()) {
            assertThat(com.isim.dims.util.MxfSpecialEvents.titleOf(name))
                    .as("重点事件 %s 缺中文标题", name)
                    .isNotEqualTo(name);
        }
    }

    @Test
    @DisplayName("application.yml 不再残留已废弃的 offline-days 键")
    void yml不含废弃键() throws Exception {
        String yml;
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(yml).doesNotContain("offline-days");
    }
}
