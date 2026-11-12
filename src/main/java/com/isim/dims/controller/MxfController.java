package com.isim.dims.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.isim.dims.dto.MxfAbnormalPanelResponse;
import com.isim.dims.dto.MxfDailyReportResponse;
import com.isim.dims.dto.MxfDashboardSummaryResponse;
import com.isim.dims.dto.MxfFilterPanelResponse;
import com.isim.dims.dto.MxfImportResponse;
import com.isim.dims.dto.MxfKeyEventsPanelResponse;
import com.isim.dims.dto.MxfOfflineNoteRequest;
import com.isim.dims.dto.MxfOperationsResponse;
import com.isim.dims.dto.MxfTrajectoryMonitorResponse;
import com.isim.dims.dto.MxfVinPanelResponse;
import com.isim.dims.dto.MxfWindowPanelResponse;
import com.isim.dims.dto.VehicleTrackResponse;
import com.isim.dims.entity.MxfOfflineNote;
import com.isim.dims.entity.MxfPacket;
import com.isim.dims.mapper.MxfPacketMapper;
import com.isim.dims.security.RequireMxfRead;
import com.isim.dims.security.RequireMxfWrite;
import com.isim.dims.service.MxfDashboardService;
import com.isim.dims.service.MxfImportService;
import com.isim.dims.service.MxfOfflineNoteService;
import com.isim.dims.service.MxfTrajectoryService;
import com.isim.dims.service.MxfVehicleDashboardService;
import com.isim.dims.service.VehicleTrackService;
import com.isim.dims.service.impl.StorageAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * MXF 埋点数据包导入 / 看板接口。
 *
 * <h3>存储架构</h3>
 * <ul>
 *   <li>导入：tar.gz → meta 解析 + 日志聚合 → 聚合快照落 {@code mxf_packet.section_stats}，
 *       轨迹点落 {@code mxf_trajectory_point}，不再逐行写明细表；
 *       <b>原始日志不落对象存储</b>（解析即弃），存量对象可用 /raw-logs/purge 回收。</li>
 *   <li>看板：按 timeRange（today/week/month/year/all）过滤 trigger_time 后
 *       在内存汇总各包的聚合快照。</li>
 * </ul>
 *
 * <h3>权限</h3>
 * <p>本控制器全部端点与既有实现一致**不挂权限注解**（{@code @RequireMxf*} 目前全被注释）。</p>
 *
 * @feature: mxf
 */
@Slf4j
@RestController
@RequestMapping("/v1/mxf")
@RequiredArgsConstructor
public class MxfController {

    private static final int MAX_LIST = 500;

    private final MxfImportService importService;
    private final MxfDashboardService dashboardService;
    private final VehicleTrackService vehicleTrackService;
    private final MxfTrajectoryService trajectoryService;
    private final MxfVehicleDashboardService vehicleDashboardService;
    private final MxfOfflineNoteService offlineNoteService;
    private final MxfPacketMapper packetMapper;
    private final StorageAccess storageAccess;

    /**
     * 批量导入 MXF 埋点数据包（.tar.gz）。
     *
     * @param files  上传的压缩包（multipart，字段名 files）
     * @param source 调用方标识（默认 manual）
     */
    @PostMapping("/packets/upload")
//    @RequireMxfWrite
    public MxfImportResponse importPackets(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "source", required = false, defaultValue = "manual") String source) {
        return importService.importPackets(files, source);
    }

    /**
     * 看板汇总数据（KPI + section 分布 + 事件分布 + 特殊事件通知）。
     *
     * @param timeRange         today|week|month|year|all（默认 all）
     * @param timeStart         自定义时间范围起点（RFC3339，如 2026-09-01T00:00:00Z）；
     *                          与 timeEnd 同时存在时忽略 timeRange
     * @param timeEnd           自定义时间范围终点（RFC3339，如 2026-09-22T23:59:59Z）
     * @param specialAlertLimit 顶部特殊事件通知最大条数（默认 20）
     */
    @GetMapping("/dashboard/summary")
//    @RequireMxfRead
    public MxfDashboardSummaryResponse getSummary(
            @RequestParam(value = "timeRange", required = false, defaultValue = "all") String timeRange,
            @RequestParam(value = "timeStart", required = false) String timeStart,
            @RequestParam(value = "timeEnd", required = false) String timeEnd,
            @RequestParam(value = "specialAlertLimit", required = false, defaultValue = "20") int specialAlertLimit) {
        return dashboardService.getSummary(timeRange, timeStart, timeEnd, specialAlertLimit);
    }

    /**
     * 智驾运营数据看板（V1.1 重写版）。
     *
     * <p>数据来源：/mmt-ads/Momenta-MXF/LUS_CX835ICA_sop/ 目录下的 MXF 文件。</p>
     *
     * @param timeRange today|week|month|year|all（默认 all）
     * @param timeStart 自定义时间范围起点（RFC3339）；与 timeEnd 同时存在时忽略 timeRange
     * @param timeEnd   自定义时间范围终点（RFC3339）
     */
    @GetMapping("/dashboard/operations")
//    @RequireMxfRead
    public MxfOperationsResponse getOperations(
            @RequestParam(value = "timeRange", required = false, defaultValue = "all") String timeRange,
            @RequestParam(value = "timeStart", required = false) String timeStart,
            @RequestParam(value = "timeEnd", required = false) String timeEnd) {
        return dashboardService.getOperations(timeRange, timeStart, timeEnd);
    }

    /**
     * 看板地图：最近 N 小时内每辆车（VIN）最新一条切片的定位轨迹。
     *
     * <p>取数：data_slice 按 vin 取 trigger_time 最新的一条 READY 切片，
     * 轨迹点来自回放产物 rosdata.json 的 {@code egoPoseMessages}
     * （/mla/egopose 经纬度，WGS-84 度制）。</p>
     *
     * @param hours       时间窗口（小时，默认 48，上限 168）
     * @param maxVehicles 最多返回车辆数（默认 50，上限 50）
     */
    @GetMapping("/dashboard/vehicle-tracks")
//    @RequireMxfRead
    public VehicleTrackResponse getVehicleTracks(
            @RequestParam(value = "hours", required = false, defaultValue = "48") int hours,
            @RequestParam(value = "maxVehicles", required = false, defaultValue = "50") int maxVehicles) {
        return vehicleTrackService.getVehicleTracks(240, 50);
    }

    /**
     * 车辆行驶轨迹监控：按 MXF 包聚合每辆车的行驶轨迹、触发事件轨迹与里程/车速统计。
     *
     * <p>轨迹点来源：MXF 文件（{@code bos:/mmt-ads/Momenta-MXF/LUS_CX835ICA_sop/}），
     * 每个 MXF 文件对应一段行驶轨迹；点取自包内日志的 {@code pg / enter_pg / exit_pg / cur_pg}
     * 字段（加偏经纬度，经度在前）。</p>
     *
     * <ul>
     *   <li>每个 MXF 包 = 一段轨迹（{@code segments}），点数 &gt;= 3 画线、否则只画起止点；</li>
     *   <li>包内多个触发事件拆到 {@code events}，选中事件可单独显示对应轨迹线；</li>
     *   <li>时间筛选按北京时间（预设 today/week/month/year/all 或自定义起止）；</li>
     *   <li>VIN 筛选：meta.vin 为 md5 时经 {@code vin_md5_mapping} 反查真实 VIN，未命中回退 meta 值。</li>
     * </ul>
     *
     * @param timeRange today|week|month|year|all（默认 week）；timeStart+timeEnd 同时给出时忽略
     * @param timeStart 自定义窗口起点（RFC3339 或北京时间 "yyyy-MM-dd[ HH:mm[:ss]]"）
     * @param timeEnd   自定义窗口终点（格式同上）
     * @param vin       车辆筛选（真实 VIN 或 meta 中的 vin_md5）
     */
    @GetMapping("/trajectory/monitor")
    public MxfTrajectoryMonitorResponse getTrajectoryMonitor(
            @RequestParam(value = "timeRange", required = false, defaultValue = "week") String timeRange,
            @RequestParam(value = "timeStart", required = false) String timeStart,
            @RequestParam(value = "timeEnd", required = false) String timeEnd,
            @RequestParam(value = "vin", required = false) String vin) {
        return trajectoryService.getMonitor(timeRange, timeStart, timeEnd, vin);
    }

    /**
     * 清理存量原始日志对象（{@code mxf/packets/**}{@code /data.log}）。
     *
     * <p>导入已不再保存原始日志，本端点用于回收历史遗留对象：删除对象存储文件并清空
     * {@code data_object_key}，包元数据、聚合快照与轨迹点全部保留。</p>
     *
     * @param limit 本次最多处理包数（默认 500；0 = 不限）
     * @return {"purged": N}
     */
    @PostMapping("/raw-logs/purge")
    public Map<String, Object> purgeRawLogs(
            @RequestParam(value = "limit", required = false, defaultValue = "500") int limit) {
        return Map.of("purged", importService.purgeRawLogs(limit));
    }

    // ============================================================
    // MXF 看板端点
    //
    // v6（运营日报看板）在用：daily-report / key-events / abnormal
    // v5 遗留（前端已不调用，保留供外部调用与回溯）：vin / filter / window
    // ⚠️ 不要因为「前端没用到」就删 vin / filter / window —— 删掉会让外部调用方直接 404。
    //
    // 各面板按「一个筛选只刷一块」拆开：切项目刷三块，切时间范围只刷日报摘要 + 分布，
    // 因此各自一个端点。
    // 明细粒度 = 包：V27 起 mxf_event_record 已删除，同一包内多次触发同一事件只占一行。
    // 原始日志不再留存，明细行仅供查看与统计，不提供日志下载。
    // 事件口径 = data_slice.event_name（包级单事件，一包最多 1 次），不是从 section_stats 派生。
    // ============================================================

    /**
     * 看板 · 「日报摘要 + 事件类型分布」（v6，同一统计窗口、同一次响应）。
     *
     * <p>窗口按<b>北京时间自然日</b>换算成 UTC、左闭右开：{@code today/week/month/all}，
     * 或 {@code startDate}+{@code endDate} 自定义（两者同时存在时优先，起止颠倒自动交换）。
     * 摘要的总触发次数与 19 个桶的合计同源，避免两者对不上。</p>
     *
     * @param sourceType 数据来源提供方 MMT|ZYT|Hirain（默认不过滤；前端默认 MMT）
     * @param timeRange  today|week|month|all|custom（默认 today）
     * @param startDate  自定义开始日（北京时间 yyyy-MM-dd）
     * @param endDate    自定义结束日（含；北京时间 yyyy-MM-dd）
     */
    @GetMapping("/vehicle-dashboard/daily-report")
//    @RequireMxfRead
    public MxfDailyReportResponse getVehicleDailyReport(
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "timeRange", required = false, defaultValue = "today") String timeRange,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate) {
        return vehicleDashboardService.getDailyReport(sourceType, timeRange, startDate, endDate);
    }

    /**
     * 看板 · 「重点事件统计」（v6）：固定 8 个事件计数 + 明细（含软件版本列）。
     *
     * <p>窗口跟随顶部时间范围，与「事件类型分布」同一窗口：
     * ① {@code startDate}+{@code endDate} → 自定义（北京自然日，含结束日，起止颠倒自动交换）；
     * ② {@code timeRange=today|week|month|all} → 北京时间自然日整段；
     * ③ <b>三个时间参数全空</b> → 滚动 24h（{@code [now-24h, now)}，preset=rolling24h），
     * 保留给旧调用方。</p>
     *
     * <p>⚠️ {@code timeRange} <b>刻意不给 defaultValue</b>（与 /daily-report 写法不同）：
     * 一旦默认成 today，不传时间参数的旧调用方会从「滚动 24h」静默变成「今天」，属破坏性变更。</p>
     *
     * @param sourceType  数据来源提供方 MMT|ZYT|Hirain（默认不过滤）
     * @param recordLimit 明细最大行数，空则取配置默认值（内部夹到 [1,500]）
     * @param timeRange   today|week|month|all；空则看日期参数或退回滚动 24h
     * @param startDate   自定义开始日（北京时间 yyyy-MM-dd）
     * @param endDate     自定义结束日（含；北京时间 yyyy-MM-dd）
     */
    @GetMapping("/vehicle-dashboard/key-events")
//    @RequireMxfRead
    public MxfKeyEventsPanelResponse getVehicleKeyEvents(
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "recordLimit", required = false) Integer recordLimit,
            @RequestParam(value = "timeRange", required = false) String timeRange,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate) {
        return vehicleDashboardService.getKeyEvents(
                sourceType, recordLimit, timeRange, startDate, endDate);
    }

    /**
     * 看板 · 「重点事件统计」导出 Excel（xlsx）。
     *
     * <p>列与页面表格一致：事件 / VIN / 触发时刻 / 软件版本。筛选口径与
     * {@code /key-events} 完全同源（项目 + 时间范围 + 可选事件类型），
     * 区别是<b>不受页面明细行数上限限制</b>（页面 200 行，导出取筛选条件下的全部，内部上限 2 万行）。</p>
     *
     * @param sourceType  数据来源提供方 MMT|ZYT|Hirain（默认不过滤）
     * @param recordLimit 导出上限，空则 20000（内部夹到 [1,20000]）
     * @param timeRange   today|week|month|all；空则看日期参数或退回滚动 24h
     * @param startDate   自定义开始日（北京时间 yyyy-MM-dd）
     * @param endDate     自定义结束日（含）
     * @param event       可选：只导出某个事件（页面「全部事件类型」下拉的选中值）
     */
    @GetMapping("/vehicle-dashboard/key-events/export")
//    @RequireMxfRead
    public ResponseEntity<byte[]> exportVehicleKeyEvents(
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "recordLimit", required = false) Integer recordLimit,
            @RequestParam(value = "timeRange", required = false) String timeRange,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "event", required = false) String event) {
        byte[] body = vehicleDashboardService.exportKeyEventsXlsx(
                sourceType, recordLimit, timeRange, startDate, endDate, event);

        String fileName = "重点事件统计.xlsx";
        return ResponseEntity.ok()
                // filename* 按 RFC 5987 编码，中文文件名在 Chrome/Edge/Firefox 都不会乱码
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    /**
     * 看板 · 「24H 积压数据统计」：积压车辆 + 已忽略车辆 + 阈值。
     *
     * <p>积压判定对每 VIN 的最新包做全量比对，<b>不受时间范围与选中车辆影响</b>，
     * 但受 {@code sourceType} 影响。</p>
     *
     * @param sourceType 数据来源提供方 MMT|ZYT|Hirain；<b>不传 = 不过滤</b>
     *                   （刻意不给 defaultValue：否则遗留调用方的全量数字会静默变成 MMT）
     */
    @GetMapping("/vehicle-dashboard/abnormal")
//    @RequireMxfRead
    public MxfAbnormalPanelResponse getVehicleAbnormalPanel(
            @RequestParam(value = "sourceType", required = false) String sourceType) {
        return vehicleDashboardService.getAbnormalPanel(sourceType);
    }

    /**
     * 看板 · 左上「单车视角」：车辆下拉 + 选中车辆 + 该车按天趋势 + 该车包级明细。
     *
     * @param vin         目标车辆（真实 VIN 或 32 位 md5），空则取最近上传的车辆
     * @param trendDays   趋势曲线天数，空则取配置默认值（内部夹到 [1,90]）
     * @param recordLimit 明细最大行数，空则取配置默认值（内部夹到 [1,500]）
     */
    @GetMapping("/vehicle-dashboard/vin")
//    @RequireMxfRead
    public MxfVinPanelResponse getVehicleVinPanel(
            @RequestParam(value = "vin", required = false) String vin,
            @RequestParam(value = "trendDays", required = false) Integer trendDays,
            @RequestParam(value = "recordLimit", required = false) Integer recordLimit) {
        return vehicleDashboardService.getVinPanel(vin, trendDays, recordLimit);
    }

    /**
     * 看板 · 中上「筛选器视角」：筛选器下拉 + 选中筛选器命中的趋势与明细。
     *
     * @param filter      选中的<b>筛选器名</b>（{@code camera_event_config.json} 的
     *                    {@code events.*.filter}，如 {@code highbeam_on_filter}）。一个筛选器
     *                    可能对应多个 {@code event_name}，服务层解析后按并集过滤；空则取
     *                    第一个筛选器
     * @param event       兼容旧调用的<b>单个事件名</b>：回填其所属筛选器，但只过滤这一个事件。
     *                    与 {@code filter} 同时传时 {@code filter} 优先
     * @param trendDays   趋势曲线天数，空则取配置默认值（内部夹到 [1,90]）
     * @param recordLimit 明细最大行数，空则取配置默认值（内部夹到 [1,500]）
     */
    @GetMapping("/vehicle-dashboard/filter")
//    @RequireMxfRead
    public MxfFilterPanelResponse getVehicleFilterPanel(
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "event", required = false) String event,
            @RequestParam(value = "trendDays", required = false) Integer trendDays,
            @RequestParam(value = "recordLimit", required = false) Integer recordLimit) {
        return vehicleDashboardService.getFilterPanel(filter, event, trendDays, recordLimit);
    }

    /**
     * 看板 · 右列「指定日期全天重点事件统计」：全天统计 + 饼图数据 + 当天明细。
     *
     * <p>统计区间是<b>北京时间自然日</b> {@code [date 00:00, date+1 00:00)}，
     * 与趋势曲线的按天分桶口径一致 —— 否则右列的「昨天」与曲线图上的「昨天」
     * 会差 8 小时，数字对不上。</p>
     *
     * @param events      逗号分隔的勾选重点事件名，空则取配置默认值
     * @param date        统计日期（北京时间 {@code yyyy-MM-dd}），空 / 非法则取「昨天」全天
     * @param recordLimit 明细最大行数，空则取配置默认值（内部夹到 [1,500]）
     */
    @GetMapping("/vehicle-dashboard/window")
//    @RequireMxfRead
    public MxfWindowPanelResponse getVehicleWindowPanel(
            @RequestParam(value = "events", required = false) String events,
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "recordLimit", required = false) Integer recordLimit) {
        return vehicleDashboardService.getWindowPanel(splitEvents(events), date, recordLimit);
    }

    /**
     * 为积压车辆填写备注 → 该车不再计入「数据异常车辆」统计与积压告警判定。
     *
     * <p>按 {@code vin} 唯一，重复提交即覆盖备注。落 {@code mxf_offline_note} 表。</p>
     *
     * @param request body {@code {vin, note}}，{@code note} 非空且去空白后 ≤ 500 字符
     * @param jwt     当前登录身份，用于记录 {@code created_by}（匿名调用时为 null）
     */
    @PostMapping("/offline-notes")
//    @RequireMxfWrite
    public MxfOfflineNote saveOfflineNote(@RequestBody MxfOfflineNoteRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return offlineNoteService.saveNote(request.getVin(), request.getNote(), subjectOf(jwt));
    }

    /**
     * 撤销某辆车的忽略状态（该车重新纳入异常统计）。
     *
     * <p>幂等：本来没有备注时同样返回 204。</p>
     */
    @DeleteMapping("/offline-notes/{vin}")
//    @RequireMxfWrite
    public ResponseEntity<Void> deleteOfflineNote(@PathVariable("vin") String vin) {
        offlineNoteService.removeNote(vin);
        return ResponseEntity.noContent().build();
    }

    /**
     * 数据包列表（最近导入优先，最多 500 条）。
     */
    @GetMapping("/packets")
//    @RequireMxfRead
    public List<MxfPacket> listPackets(
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        int safe = Math.min(Math.max(1, limit), MAX_LIST);
        return packetMapper.selectList(new QueryWrapper<MxfPacket>()
                .orderByDesc("created_at")
                .last("LIMIT " + safe));
    }

    /**
     * 删除数据包（连带清理 MinIO 上可能残留的日志对象）。
     */
    @DeleteMapping("/packets/{id}")
//    @RequireMxfWrite
    public ResponseEntity<Void> deletePacket(@PathVariable("id") String id) {
        MxfPacket packet = packetMapper.selectById(id);
        if (packet == null) {
            return ResponseEntity.notFound().build();
        }
        if (packet.getDataObjectKey() != null) {
            try {
                storageAccess.delete(packet.getDataObjectKey());
            } catch (Exception e) {
                // 对象删除失败不阻断主记录删除，记录日志即可
                log.warn("[MXF] delete object failed, key={}", packet.getDataObjectKey(), e);
            }
        }
        packetMapper.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 逗号分隔的事件名 → 列表（空串 / 空白段丢弃）。
     */
    private static List<String> splitEvents(String events) {
        if (events == null || events.isBlank()) {
            return null;
        }
        return java.util.Arrays.stream(events.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 取 JWT subject（匿名 / 无主体时为 null）。
     *
     * <p>与 {@code UserDashboardConfigController#subjectOf} 同语义：常规读接口 permitAll，
     * 匿名请求下 {@code @AuthenticationPrincipal} 注入的是匿名 principal，转不成 {@link Jwt}
     * 时为 null —— 不能直接 {@code jwt.getSubject()}。</p>
     */
    private static String subjectOf(Jwt jwt) {
        return jwt == null ? null : jwt.getSubject();
    }
}
