package com.isim.dims.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 单车 MXF 埋点看板配置（{@code dims.mxf.vehicle.*}）。
 *
 * <p>阈值按需求方要求**只做后端配置**（前端只读展示当前值），改配置后需重启服务生效。</p>
 *
 * @feature: mxf
 */
@Component
@ConfigurationProperties(prefix = "dims.mxf.vehicle")
@Data
public class MxfVehicleDashboardConfig {

    /**
     * 积压判定阈值（天）：某 VIN **最新上传的那个包**，其「上传时间（{@code created_at}）−
     * 触发时间（{@code effectiveTime}）」超过该天数即判定为数据积压待传。
     *
     * <p>注意与 v1 的「最新上传距今 N 天 = 掉线」是两个不同语义，v2 已按需求方要求**替换**。</p>
     */
    private int backlogDelayDays = 2;

    /**
     * 积压告警阈值（辆）：异常车辆数超过该值即提示「车辆积压待传数据过多」。
     */
    private int backlogVehicles = 20;

    /**
     * 右列「全天重点事件统计」的默认统计日期 = 今天 − N 天（按<b>北京时间</b>取日）。
     *
     * <p>默认 1，即前端不传 {@code date} 时统计<b>昨天全天</b>；改成 0 则默认统计今天。
     * 之所以用「偏移天数」而不是「小时数」：右列的粒度已经被需求方定为自然日，
     * 残留一个可配的小时数只会让「窗口是什么」变得含糊。</p>
     */
    private int defaultDateOffsetDays = 1;

    /**
     * 趋势曲线默认天数（按天聚合，缺失日补 0）。需求口径：曲线只展示最近 3 天。
     */
    private int trendDays = 3;

    /**
     * 「过去 24H 重点事件统计」的固定事件清单（顺序即 UI 顺序与表格顺序）。
     *
     * <p>2026-10 原型改版后为 <b>8 项</b>（原型里叫「AEB碰撞风险 / AES触发事件 / ESS触发事件 /
     * LAEB制动事件 / UNP接管/碰撞事件 / DSSAD触发 / IMU碰撞风险 / 系统崩溃」）。</p>
     *
     * <p>⚠️ 第 3 项在原型里写作 {@code fusion_aeb}，实际库值是 <b>{@code fushion_aeb}（双 u）</b>：
     * 平台侧 {@code camera_event_config.json} 的原始 key 就是它，2026-09-18 已撤销改名，
     * V28 迁移里还留着 {@code fusion_aeb → fushion_aeb} 的反向兜底 UPDATE。不要"顺手纠正拼写"。</p>
     */
    private List<String> eventOptions = new ArrayList<>(List.of(
            "imu_collision",
            "system_crash",
            "fushion_aeb",
            "cp_aes_trig_event",
            "cp_ess_trig_event",
            "meb_brake_event",
            "unp_tkov_colli",
            "dssad_collision"));

    // ==== v5（单车看板）的 6 项：原型改版后不再使用，按需求方要求保留备查，勿删 ====
    // imu_collision, system_crash, cp_aes_trig_event,
    // cp_ess_trig_event, unp_tkov_colli, fushion_aeb
    // （与 8 项的差异：新增 fushion_aeb 之外只多 meb_brake_event / dssad_collision，
    //   即「LAEB制动事件」「DSSAD触发」两个原型新增项）

    /**
     * 默认勾选的重点事件（当前 = 全部 8 项）。
     *
     * <p>v6 页面不再提供勾选交互（固定展示 8 项），该字段保留给旧 {@code /window}
     * 端点与外部调用方。</p>
     */
    private List<String> defaultEvents = new ArrayList<>(List.of(
            "imu_collision",
            "system_crash",
            "fushion_aeb",
            "cp_aes_trig_event",
            "cp_ess_trig_event",
            "meb_brake_event",
            "unp_tkov_colli",
            "dssad_collision"));

    // ==== v5 旧默认勾选（6 项，保留备查） ====
    // imu_collision, system_crash, cp_aes_trig_event,
    // cp_ess_trig_event, unp_tkov_colli, fushion_aeb

    /**
     * 内存聚合的包数量上限（按 created_at 倒序取最新 N 个包）。
     */
    private int maxPackets = 5000;

    /**
     * 各明细表默认最大返回行数。
     */
    private int maxRecords = 200;
}
