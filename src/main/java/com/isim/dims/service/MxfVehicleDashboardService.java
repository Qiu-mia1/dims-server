package com.isim.dims.service;

import com.isim.dims.dto.MxfAbnormalPanelResponse;
import com.isim.dims.dto.MxfDailyReportResponse;
import com.isim.dims.dto.MxfFilterPanelResponse;
import com.isim.dims.dto.MxfKeyEventsPanelResponse;
import com.isim.dims.dto.MxfVinPanelResponse;
import com.isim.dims.dto.MxfWindowPanelResponse;

import java.util.List;

/**
 * 单车 MXF 埋点看板服务。
 *
 * <h3>端点总览（v6：vin / filter 已不再被前端调用，仅保留供外部与回溯）</h3>
 * <table border="1">
 *   <caption>面板 ↔ 筛选条件 ↔ 端点</caption>
 *   <tr><th>面板</th><th>筛选条件</th><th>端点</th></tr>
 *   <tr><td>日报摘要 + 19 类事件分布（v6）</td><td>{@code sourceType} + {@code timeRange}/{@code startDate,endDate}</td>
 *       <td>{@code GET /v1/mxf/vehicle-dashboard/daily-report}</td></tr>
 *   <tr><td>过去 24H 重点事件统计（v6）</td><td>{@code sourceType}</td>
 *       <td>{@code GET /v1/mxf/vehicle-dashboard/key-events}</td></tr>
 *   <tr><td>24H 积压数据统计（v6）</td><td>{@code sourceType}（可选）</td>
 *       <td>{@code GET /v1/mxf/vehicle-dashboard/abnormal}</td></tr>
 *   <tr><td>左上 单车视角（v5 遗留）</td><td>{@code vin}</td>
 *       <td>{@code GET /v1/mxf/vehicle-dashboard/vin}</td></tr>
 *   <tr><td>中上 筛选器视角（v5 遗留）</td><td>{@code filter}（或兼容的 {@code event}）</td>
 *       <td>{@code GET /v1/mxf/vehicle-dashboard/filter}</td></tr>
 *   <tr><td>右列 指定日期全天重点事件统计（v5 遗留）</td><td>{@code events} + {@code date}</td>
 *       <td>{@code GET /v1/mxf/vehicle-dashboard/window}</td></tr>
 * </table>
 *
 * <p>各面板按「一个筛选只刷一块」拆开：切项目刷三块，切时间范围只刷日报摘要 + 分布，
 * 因此各方法彼此不共享筛选条件，只共享底层查询与组装工具。</p>
 *
 * <p>明细粒度 = 包（V27 起 {@code mxf_event_record} 已删除），
 * 事件口径 = {@code mxf_packet.event_name}（包级单事件，一个包最多 1 次）。
 * 详见 {@code docs/MXF-VEHICLE-DASHBOARD-DESIGN.md}。</p>
 *
 * @feature: mxf
 */
public interface MxfVehicleDashboardService {

    /**
     * 左上「单车视角」面板：车辆下拉 + 选中车辆 + 该车按天趋势 + 该车包级明细。
     *
     * @param vin         目标车辆（真实 VIN 或 32 位 md5），空 / 未命中则取最近上传的车辆
     * @param trendDays   趋势曲线天数，空则取配置默认值
     * @param recordLimit 明细最大行数，空则取配置默认值（内部夹到 [1,500]）
     * @return 单车面板数据
     */
    MxfVinPanelResponse getVinPanel(String vin, Integer trendDays, Integer recordLimit);

    /**
     * 中上「筛选器视角」面板：筛选器下拉 + 选中筛选器命中的趋势与明细。
     *
     * @param filter      选中的<b>筛选器名</b>（{@code camera_event_config.json} 的
     *                    {@code events.*.filter}）；服务内部映射成事件名集合后过滤
     *                    {@code mxf_packet.event_name}。空则取第一个筛选器
     * @param event       兼容旧调用的<b>单个事件名</b>：回填它所属的筛选器，
     *                    但过滤集合仍只含这一个事件。与 {@code filter} 同时传时 {@code filter} 优先
     * @param trendDays   趋势曲线天数，空则取配置默认值
     * @param recordLimit 明细最大行数，空则取配置默认值（内部夹到 [1,500]）
     * @return 筛选器面板数据
     */
    MxfFilterPanelResponse getFilterPanel(String filter, String event,
                                         Integer trendDays, Integer recordLimit);

    /**
     * 右列「指定日期全天重点事件统计」面板：全天统计 + 饼图数据 + 当天明细。
     *
     * <p>统计区间为**北京时间自然日** {@code [date 00:00, date+1 00:00)}，内部换算成 UTC
     * 与 {@code data_slice} 的 UTC 时间列比较。之所以按北京时间切天，是为了和趋势曲线的
     * 按天分桶口径一致（两者都是「显示给用户的某一天」），否则右列的「昨天」与曲线图上
     * 的「昨天」会差 8 小时、数字对不上。</p>
     *
     * @param events      勾选的重点事件名，空则取配置默认值
     * @param date        统计日期，<b>北京时间</b> {@code yyyy-MM-dd}；空 / 非法 / 越界
     *                    （年份超出 [2000, 明年]）则回退到「今天 −
     *                    {@code dims.mxf.vehicle.default-date-offset-days}」（默认昨天）
     * @param recordLimit 明细最大行数，空则取配置默认值（内部夹到 [1,500]）
     * @return 全天统计面板数据
     */
    MxfWindowPanelResponse getWindowPanel(List<String> events, String date, Integer recordLimit);

    /**
     * 左下「数据异常车辆」面板：积压车辆 + 已忽略车辆 + 阈值。
     *
     * <p>积压判定对每 VIN 的**最新包**做全量比对，不受时间范围与选中车辆影响。
     * 保留无参重载 = 不按来源过滤（向后兼容），新页面请用
     * {@link #getAbnormalPanel(String)}。</p>
     *
     * @return 异常车辆面板数据
     */
    MxfAbnormalPanelResponse getAbnormalPanel();

    /**
     * 同上，但按数据来源提供方（MMT/ZYT/Hirain）过滤。
     *
     * @param sourceType 数据来源提供方；null / 空白 / {@code all} = 不过滤
     * @return 异常车辆面板数据
     */
    MxfAbnormalPanelResponse getAbnormalPanel(String sourceType);

    // ------------------------------------------------------------------
    // v6 运营日报看板（前端 MxfVehicleDashboard.vue 已改用下面两个端点）
    // ------------------------------------------------------------------

    /**
     * 日报摘要 + 事件类型分布（同一窗口、同一次响应）。
     *
     * <p>窗口按<b>北京时间自然日</b>换算成 UTC、左闭右开：
     * {@code today=[今天00:00,明天00:00)}、{@code week=[本周一,下周一)}、
     * {@code month=[本月1日,下月1日)}、{@code all=[1970-01-01,9999-12-31]}；
     * {@code startDate}+{@code endDate} 同时存在时优先级最高（起止颠倒自动交换）。</p>
     *
     * @param sourceType 数据来源提供方（MMT/ZYT/Hirain）；null / 空白 / {@code all} = 不过滤
     * @param timeRange  today | week | month | all；非法值按 today
     * @param startDate  自定义开始日（北京时间 {@code yyyy-MM-dd}）
     * @param endDate    自定义结束日（含；北京时间 {@code yyyy-MM-dd}）
     * @return 摘要 + 19 类固定分布
     */
    MxfDailyReportResponse getDailyReport(String sourceType, String timeRange,
                                          String startDate, String endDate);

    /**
     * 「重点事件统计」：固定 8 个事件 + 明细。
     *
     * <p>兼容旧签名：等价于 {@code getKeyEvents(sourceType, recordLimit, null, null, null)}，
     * 即滚动 24 小时 {@code [now-24h, now)}。</p>
     *
     * @param sourceType  数据来源提供方；null / 空白 / {@code all} = 不过滤
     * @param recordLimit 明细最大行数，空则取配置默认值（内部夹到 [1,500]）
     * @return 重点事件统计
     */
    MxfKeyEventsPanelResponse getKeyEvents(String sourceType, Integer recordLimit);

    /**
     * 同上，但窗口跟随顶部时间范围（与「事件类型分布」同一窗口）。
     *
     * <p>三个时间参数<b>全空</b>时退回滚动 24 小时（兼容旧调用，preset={@code rolling24h}）；
     * 否则复用日报的窗口解析：{@code startDate}+{@code endDate} 优先（北京自然日、含结束日、
     * 起止颠倒自动交换），其次 {@code today/week/month/all}。</p>
     *
     * @param sourceType  数据来源提供方；null / 空白 / {@code all} = 不过滤
     * @param recordLimit 明细最大行数，空则取配置默认值（内部夹到 [1,500]）
     * @param timeRange   today | week | month | all；空 = 由日期参数或滚动 24h 决定
     * @param startDate   自定义开始日（北京时间 {@code yyyy-MM-dd}）
     * @param endDate     自定义结束日（含；北京时间 {@code yyyy-MM-dd}）
     * @return 重点事件统计
     */
    MxfKeyEventsPanelResponse getKeyEvents(String sourceType, Integer recordLimit,
                                           String timeRange, String startDate, String endDate);

    /**
     * 把「重点事件统计」当前筛选结果导出成 xlsx（列与页面表格一致：事件 / VIN / 触发时刻 / 软件版本）。
     *
     * <p>筛选条件与 {@link #getKeyEvents} 完全同源（项目 + 时间范围 + 可选的事件类型），
     * 区别只是<b>不受页面明细行数上限限制</b>：页面为了渲染只取 {@code recordLimit} 行，
     * 导出取全部命中行（内部有 20000 行安全上限，防止误点把几十万行打进内存）。</p>
     *
     * @param sourceType  数据来源提供方；null / 空白 / {@code all} = 不过滤
     * @param recordLimit 导出上限（内部夹到 [1, 20000]，空则 20000）
     * @param timeRange   today | week | month | all；空 = 由日期参数或滚动 24h 决定
     * @param startDate   自定义开始日（北京时间 {@code yyyy-MM-dd}）
     * @param endDate     自定义结束日（含；北京时间 {@code yyyy-MM-dd}）
     * @param event       可选：只导出某一个事件（页面「全部事件类型」下拉的选中值），
     *                    不在 8 项清单内时按全部导出并打 WARN
     * @return xlsx 二进制内容
     */
    byte[] exportKeyEventsXlsx(String sourceType, Integer recordLimit, String timeRange,
                               String startDate, String endDate, String event);
}
