package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.MxfTrajectoryMonitorResponse;
import com.isim.dims.dto.MxfTrajectoryMonitorResponse.EventItem;
import com.isim.dims.dto.MxfTrajectoryMonitorResponse.PointItem;
import com.isim.dims.dto.MxfTrajectoryMonitorResponse.Segment;
import com.isim.dims.dto.MxfTrajectoryMonitorResponse.Summary;
import com.isim.dims.dto.MxfTrajectoryMonitorResponse.VehicleTrack;
import com.isim.dims.entity.MxfPacket;
import com.isim.dims.entity.MxfTrajectoryPoint;
import com.isim.dims.entity.VinMd5Mapping;
import com.isim.dims.mapper.MxfPacketMapper;
import com.isim.dims.mapper.MxfTrajectoryPointMapper;
import com.isim.dims.mapper.VinMd5MappingMapper;
import com.isim.dims.service.MxfTrajectoryService;
import com.isim.dims.util.MxfSpecialEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MXF 行驶轨迹监控服务实现
 *
 * <p>数据链路：MxfImportServiceImpl 解析包内日志时抽取 pg / enter_pg /
 * exit_pg / cur_pg 经纬度点落 {@code mxf_trajectory_point} → 本服务按窗口 +
 * VIN 聚合为 车辆 → 轨迹段（MXF 包）→ 轨迹点 / 触发事件 的三级结构，
 * 供「车辆行驶轨迹监控」页渲染（画线 / 画点 / 事件轨迹联动 / VIN 筛选 / 时间筛选）。
 *
 * <p>坐标口径：MXF 定义中的 pg 字段为加偏经纬度（经度在前），与高德底图直接对齐。
 *
 * @feature: mxf
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MxfTrajectoryServiceImpl implements MxfTrajectoryService {

    /** 北京时区（触发时间 / 时间筛选均按北京时间口径） */
    private static final ZoneId ZONE_BEIJING = ZoneId.of("Asia/Shanghai");
    private static final ZoneId ZONE_UTC = ZoneId.of("UTC");
    private static final DateTimeFormatter CN_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 32 位 hex：meta.vin 存的是 vin_md5 时用于反查 vin_md5_mapping */
    private static final Pattern MD5_HEX = Pattern.compile("^[0-9a-fA-F]{32}$");

    /** 单轨迹段响应抽稀上限（完整点集参与里程/车速统计后再抽稀） */
    private static final int MAX_SEGMENT_POINTS = 500;
    /** 单事件轨迹响应抽稀上限 */
    private static final int MAX_EVENT_POINTS = 200;
    /** 小于 3 个点只画起止点（需求 5） */
    private static final int LINE_MIN_POINTS = 3;
    /** 相邻两点距离超过该值视为 GPS 跳变，不参与里程/车速统计 */
    private static final double MAX_JUMP_KM = 20.0;
    /** 车速上限（km/h）：GPS 跳变判定 + RUNNING-INFO 口径的异常值截断 */
    private static final double MAX_PLAUSIBLE_SPEED_KMH = 140.0;
    /** RUNNING-INFO 的 dis_in_last_5s_(m) 统计窗口（秒） */
    private static final double DIS_IN_LAST_5S_WINDOW_S = 5.0;
    /** mxf_packet.section_stats 中「过去 5s 里程包内最大值（m）」的 key */
    private static final String STATS_MAX_DIS_IN_LAST_5S_M = "maxDisInLast5sM";

    private final MxfPacketMapper mxfPacketMapper;
    private final MxfTrajectoryPointMapper trajectoryPointMapper;
    private final VinMd5MappingMapper vinMd5MappingMapper;
    private final ObjectMapper objectMapper;

    @Override
    public MxfTrajectoryMonitorResponse getMonitor(String timeRange, String timeStart, String timeEnd, String vin) {
        Window window = resolveWindow(timeRange, timeStart, timeEnd);

        List<MxfPacket> packets = loadPackets(window, vin);
        Map<String, List<MxfPacket>> byVehicle = groupByVehicle(packets);

        List<VehicleTrack> vehicles = new ArrayList<>();
        long totalPoints = 0;
        long totalEvents = 0;
        double totalDistanceKm = 0;
        int vehiclesWithPoints = 0;

        for (Map.Entry<String, List<MxfPacket>> entry : byVehicle.entrySet()) {
            VehicleTrack track = buildVehicleTrack(entry.getKey(), entry.getValue());
            vehicles.add(track);
            totalPoints += track.getPointCount();
            totalEvents += track.getEvents() == null ? 0 : track.getEvents().size();
            totalDistanceKm += track.getDistanceKm();
            if (track.getPointCount() > 0) {
                vehiclesWithPoints++;
            }
        }

        Summary summary = Summary.builder()
                .vehicleCount(vehiclesWithPoints)
                .totalVehicleCount(byVehicle.size())
                .packetCount(packets.size())
                .totalDistanceKm(round2(totalDistanceKm))
                .totalPointCount(totalPoints)
                .totalEventCount(totalEvents)
                .build();

        return MxfTrajectoryMonitorResponse.builder()
                .timeStart(window.startDisplay())
                .timeEnd(window.endDisplay())
                .generatedAt(LocalDateTime.now(ZONE_BEIJING).format(CN_FORMAT))
                .summary(summary)
                .vehicles(vehicles)
                .build();
    }

    // ==================================================================
    // 查询窗口
    // ==================================================================

    /**
     * 查询窗口：内部统一 UTC 时间用于 DB 比较；display 为北京时间回显串。
     */
    private record Window(LocalDateTime start, LocalDateTime end,
                          String startDisplay, String endDisplay) {
    }

    private Window resolveWindow(String timeRange, String timeStart, String timeEnd) {
        if (StringUtils.hasText(timeStart) && StringUtils.hasText(timeEnd)) {
            LocalDateTime start = parseFlexibleTime(timeStart);
            LocalDateTime end = parseFlexibleTime(timeEnd);
            return new Window(start, end,
                    start == null ? null : cn(start), end == null ? null : cn(end));
        }
        String range = timeRange == null ? "all" : timeRange;
        if ("all".equals(range)) {
            return new Window(null, null, null, null);
        }
        // 预设口径：北京时间自然日（today 为当日 00:00 起）
        LocalDateTime todayStart = LocalDate.now(ZONE_BEIJING).atStartOfDay();
        LocalDateTime start = switch (range) {
            case "today" -> todayStart;
            case "week" -> todayStart.minusDays(6);
            case "month" -> todayStart.minusDays(29);
            case "year" -> todayStart.minusDays(364);
            default -> null;
        };
        if (start == null) {
            return new Window(null, null, null, null);
        }
        LocalDateTime end = todayStart.plusDays(1);
        // 北京时间 → UTC（减 8 小时）后与 DB 的 UTC 列比较
        return new Window(start.minusHours(8), end.minusHours(8), cn(start), cn(end));
    }

    /**
     * 宽松时间解析：RFC3339/ISO（带 Z 或偏移）→ 转北京时间；
     * 无时区视作北京时间 {@code yyyy-MM-dd[ HH:mm[:ss]]}，返回值统一转 UTC。
     */
    private LocalDateTime parseFlexibleTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String v = raw.trim();
        try {
            if (v.endsWith("Z") || v.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                try {
                    return Instant.parse(v).atZone(ZONE_BEIJING).toLocalDateTime().minusHours(8);
                } catch (Exception ignore) {
                    return OffsetDateTime.parse(v)
                            .atZoneSameInstant(ZONE_BEIJING).toLocalDateTime().minusHours(8);
                }
            }
            v = v.replace('T', ' ');
            String[] parts = v.split(" ");
            if (parts.length == 1) {
                return LocalDate.parse(parts[0]).atStartOfDay().minusHours(8);
            }
            String time = parts[1].split(":").length == 2 ? parts[1] + ":00" : parts[1];
            return LocalDateTime.parse(parts[0] + " " + time, CN_FORMAT).minusHours(8);
        } catch (Exception e) {
            log.warn("[MxfTrajectory] 无法解析时间参数 [{}]，忽略该窗口边界", raw);
            return null;
        }
    }

    /** UTC LocalDateTime → 北京时间串 */
    private String cn(LocalDateTime utc) {
        return utc.atZone(ZONE_UTC).withZoneSameInstant(ZONE_BEIJING).format(CN_FORMAT);
    }

    // ==================================================================
    // 数据装配
    // ==================================================================

    private List<MxfPacket> loadPackets(Window window, String vin) {
        LambdaQueryWrapper<MxfPacket> qw = new LambdaQueryWrapper<MxfPacket>()
                .eq(MxfPacket::getStatus, "PARSED")
                .orderByAsc(MxfPacket::getTriggerTime);
        if (window.start() != null) {
            qw.ge(MxfPacket::getTriggerTime, window.start());
        }
        if (window.end() != null) {
            qw.lt(MxfPacket::getTriggerTime, window.end());
        }
        if (StringUtils.hasText(vin)) {
            String v = vin.trim();
            // 新数据 vin 已是明文；历史包 vin 仍为摘要，需同时按反查到的 md5 匹配
            String md5 = lookupMd5ByVin(v);
            qw.and(w -> {
                w.eq(MxfPacket::getVin, v).or().eq(MxfPacket::getVinMd5, v);
                if (md5 != null) {
                    w.or().eq(MxfPacket::getVin, md5).or().eq(MxfPacket::getVinMd5, md5);
                }
            });
        }
        return mxfPacketMapper.selectList(qw);
    }

    /**
     * 按展示 VIN 分组。
     * <p>导入流程已把 meta.vin 经 {@code vin_md5_mapping} 换成明文 VIN 落库，
     * 新数据直接使用；仅历史包（vin 仍是 32 位摘要）才即时反查，未命中回退原值。</p>
     */
    private Map<String, List<MxfPacket>> groupByVehicle(List<MxfPacket> packets) {
        Map<String, List<MxfPacket>> byVehicle = new LinkedHashMap<>();
        Map<String, String> cache = new LinkedHashMap<>();
        for (MxfPacket p : packets) {
            String raw = p.getVin();
            String display = cache.computeIfAbsent(raw == null ? "" : raw, k -> resolveDisplayVin(p));
            byVehicle.computeIfAbsent(display, k -> new ArrayList<>()).add(p);
        }
        return byVehicle;
    }

    private String resolveDisplayVin(MxfPacket packet) {
        String vin = packet.getVin();
        if (vin != null && !vin.isBlank() && !MD5_HEX.matcher(vin).matches()) {
            return vin;
        }
        // meta.vin 是 md5（或缺失）→ 走映射表反查
        String mapped = lookupVinByMd5(vin != null ? vin : packet.getVinMd5());
        if (mapped == null && vin != null) {
            mapped = lookupVinByMd5(packet.getVinMd5());
        }
        if (mapped != null) {
            return mapped;
        }
        if (vin != null && !vin.isBlank()) {
            return vin;
        }
        return packet.getVinMd5() == null ? "(未知VIN)" : packet.getVinMd5();
    }

    private String lookupVinByMd5(String md5) {
        if (!StringUtils.hasText(md5) || !MD5_HEX.matcher(md5).matches()) {
            return null;
        }
        VinMd5Mapping mapping = vinMd5MappingMapper.selectOne(
                new LambdaQueryWrapper<VinMd5Mapping>()
                        .eq(VinMd5Mapping::getMd5, md5)
                        .last("LIMIT 1"));
        return mapping == null ? null : mapping.getVin();
    }

    /** 真实 VIN → vin_md5（用于按真实 VIN 过滤 mxf_packet.vin/vin_md5） */
    private String lookupMd5ByVin(String vin) {
        if (!StringUtils.hasText(vin) || MD5_HEX.matcher(vin).matches()) {
            return null;
        }
        VinMd5Mapping mapping = vinMd5MappingMapper.selectOne(
                new LambdaQueryWrapper<VinMd5Mapping>()
                        .eq(VinMd5Mapping::getVin, vin)
                        .last("LIMIT 1"));
        return mapping == null ? null : mapping.getMd5();
    }

    private VehicleTrack buildVehicleTrack(String vin, List<MxfPacket> packets) {
        List<String> packetIds = packets.stream().map(MxfPacket::getId).toList();
        Map<String, List<MxfTrajectoryPoint>> pointsByPacket = loadPoints(packetIds);

        List<Segment> segments = new ArrayList<>();
        List<EventDraft> drafts = new ArrayList<>();
        double distanceKm = 0;
        double maxSpeedKmh = 0;
        long pointCount = 0;
        long durationSeconds = 0;
        LocalDateTime firstTime = null;
        LocalDateTime lastTime = null;

        for (MxfPacket p : packets) {
            List<MxfTrajectoryPoint> pts = pointsByPacket.getOrDefault(p.getId(), List.of());
            if (pts.isEmpty()) {
                continue;
            }
            // 触发事件轨迹草稿：包内按原始 SECTION 名分组（事件列表数据来源，不再使用 event_name）
            Map<String, List<MxfTrajectoryPoint>> byEvent = new LinkedHashMap<>();
            for (MxfTrajectoryPoint pt : pts) {
                if (pt.getSection() != null && !pt.getSection().isBlank()) {
                    byEvent.computeIfAbsent(pt.getSection(), k -> new ArrayList<>()).add(pt);
                }
            }
            for (Map.Entry<String, List<MxfTrajectoryPoint>> e : byEvent.entrySet()) {
                List<MxfTrajectoryPoint> eps = e.getValue();
                LocalDateTime t = eps.get(0).getLogTs() != null
                        ? eps.get(0).getLogTs() : p.getTriggerTime();
                drafts.add(new EventDraft(e.getKey(), p.getId(), t, eps));
            }

            // 包级轨迹只取非事件点：事件行的 enter_pg/exit_pg 是进出点，
            // 混入主线会造成折返线段与里程重复；整包都是事件点时回退全量。
            List<MxfTrajectoryPoint> basePts = new ArrayList<>();
            for (MxfTrajectoryPoint pt : pts) {
                if (pt.getEventName() == null || pt.getEventName().isBlank()) {
                    basePts.add(pt);
                }
            }
            List<MxfTrajectoryPoint> linePts = basePts.isEmpty() ? pts : basePts;

            Segment seg = buildSegment(p, linePts);
            segments.add(seg);
            distanceKm += seg.getDistanceKm();
            // 最高车速：优先取 RUNNING-INFO 的 dis_in_last_5s_(m) 口径；
            // 历史包未聚合该字段时回退 GPS 相邻点推算值
            Double packetMaxSpeed = maxSpeedFromRunningInfo(p);
            maxSpeedKmh = Math.max(maxSpeedKmh,
                    packetMaxSpeed != null ? packetMaxSpeed : maxSpeedOf(linePts));
            pointCount += linePts.size();
            if (p.getPacketStart() != null && p.getPacketEnd() != null) {
                durationSeconds += ChronoUnit.SECONDS.between(p.getPacketStart(), p.getPacketEnd());
            }
            firstTime = minTime(firstTime, p.getPacketStart());
            lastTime = maxTime(lastTime, p.getPacketEnd());
        }

        // 同名事件按时间排序 → 「第 N 次」
        drafts.sort(Comparator.comparing(EventDraft::firstTs,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, Integer> counters = new LinkedHashMap<>();
        List<EventItem> events = new ArrayList<>();
        for (EventDraft d : drafts) {
            int idx = counters.merge(d.eventName(), 1, Integer::sum);
            events.add(EventItem.builder()
                    .eventName(d.eventName())
                    .title(MxfSpecialEvents.titleOf(d.eventName()))
                    .index(idx)
                    .time(d.firstTs() == null ? null : cn(d.firstTs()))
                    .packetId(d.packetId())
                    .pointCount(d.points().size())
                    .drawMode(d.points().size() >= LINE_MIN_POINTS ? "line" : "points")
                    .points(decimate(toPointItems(d.points()), MAX_EVENT_POINTS))
                    .build());
        }

        double durationHours = durationSeconds / 3600.0;
        // 来源标记：vin 已不是 32 位摘要 = 导入时经 vin_md5_mapping 解析成明文；
        // 仍是摘要说明映射未命中，展示的是 meta 原值
        String rawVin = packets.isEmpty() ? null : packets.get(0).getVin();
        boolean fromMapping = rawVin != null && !MD5_HEX.matcher(rawVin).matches();
        return VehicleTrack.builder()
                .vin(vin)
                .vinSource(fromMapping ? "mapping" : "meta")
                .packetCount(packets.size())
                .pointCount(pointCount)
                .distanceKm(round2(distanceKm))
                .durationHours(round2(durationHours))
                .avgSpeedKmh(round1(durationHours > 0 ? distanceKm / durationHours : 0))
                .maxSpeedKmh(round1(maxSpeedKmh))
                .firstTime(firstTime == null ? null : cn(firstTime))
                .lastTime(lastTime == null ? null : cn(lastTime))
                .segments(segments)
                .events(events)
                .build();
    }

    private Map<String, List<MxfTrajectoryPoint>> loadPoints(List<String> packetIds) {
        Map<String, List<MxfTrajectoryPoint>> result = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(packetIds)) {
            return result;
        }
        int batch = 200;
        for (int i = 0; i < packetIds.size(); i += batch) {
            List<String> sub = packetIds.subList(i, Math.min(packetIds.size(), i + batch));
            List<MxfTrajectoryPoint> pts = trajectoryPointMapper.selectList(
                    new LambdaQueryWrapper<MxfTrajectoryPoint>()
                            .in(MxfTrajectoryPoint::getPacketId, sub)
                            .orderByAsc(MxfTrajectoryPoint::getId));
            for (MxfTrajectoryPoint pt : pts) {
                result.computeIfAbsent(pt.getPacketId(), k -> new ArrayList<>()).add(pt);
            }
        }
        return result;
    }

    private Segment buildSegment(MxfPacket p, List<MxfTrajectoryPoint> pts) {
        return Segment.builder()
                .packetId(p.getId())
                .fileName(p.getFileName())
                .triggerTime(p.getTriggerTime() == null ? null : cn(p.getTriggerTime()))
                .startTime(p.getPacketStart() == null ? null : cn(p.getPacketStart()))
                .endTime(p.getPacketEnd() == null ? null : cn(p.getPacketEnd()))
                .pointCount(pts.size())
                .drawMode(pts.size() >= LINE_MIN_POINTS ? "line" : "points")
                .distanceKm(round2(haversineKm(pts)))
                .points(decimate(toPointItems(pts), MAX_SEGMENT_POINTS))
                .build();
    }

    private List<PointItem> toPointItems(List<MxfTrajectoryPoint> pts) {
        List<PointItem> items = new ArrayList<>(pts.size());
        for (MxfTrajectoryPoint pt : pts) {
            items.add(PointItem.builder()
                    .ts(pt.getLogTs() == null ? null : cn(pt.getLogTs()))
                    .lat(pt.getLat() == null ? Double.NaN : pt.getLat())
                    .lon(pt.getLon() == null ? Double.NaN : pt.getLon())
                    .eventName(pt.getEventName())
                    .pointType(pt.getPointType())
                    .build());
        }
        return items;
    }

    /** 均匀抽稀（保留首尾点） */
    private List<PointItem> decimate(List<PointItem> items, int cap) {
        if (cap <= 0 || items.size() <= cap) {
            return items;
        }
        double stride = (double) (items.size() - 1) / (cap - 1);
        List<PointItem> out = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            out.add(items.get((int) Math.round(i * stride)));
        }
        return out;
    }

    // ==================================================================
    // 几何 / 速度
    // ==================================================================

    private double haversineKm(List<MxfTrajectoryPoint> pts) {
        double total = 0;
        for (int i = 1; i < pts.size(); i++) {
            MxfTrajectoryPoint a = pts.get(i - 1);
            MxfTrajectoryPoint b = pts.get(i);
            if (a.getLat() == null || b.getLat() == null) {
                continue;
            }
            double km = haversineKm(a.getLat(), a.getLon(), b.getLat(), b.getLon());
            if (isGpsJump(a, b, km)) {
                continue;
            }
            total += km;
        }
        return total;
    }

    /**
     * 判断相邻两点是否为 GPS 跳变：距离过大，或按时间差推算的车速明显不合理。
     * 跳变点仍保留在轨迹线中（保证图形与真实日志一致），仅不计入里程/车速统计。
     */
    private boolean isGpsJump(MxfTrajectoryPoint a, MxfTrajectoryPoint b, double km) {
        if (km > MAX_JUMP_KM) {
            return true;
        }
        if (a.getLogTs() == null || b.getLogTs() == null) {
            return false;
        }
        long seconds = ChronoUnit.SECONDS.between(a.getLogTs(), b.getLogTs());
        if (seconds <= 0) {
            return false;
        }
        return km / (seconds / 3600.0) > MAX_PLAUSIBLE_SPEED_KMH;
    }

    /**
     * 最高车速：RUNNING-INFO 的 {@code dis_in_last_5s_(m)} 包内最大值 / 5s → m/s → km/h。
     * 该最大值由导入聚合写入 {@code mxf_packet.section_stats.maxDisInLast5sM}。
     *
     * @return 车速（km/h）；历史包无该快照字段或值非法时返回 null，由调用方回退 GPS 推算
     */
    private Double maxSpeedFromRunningInfo(MxfPacket p) {
        String stats = p.getSectionStats();
        if (!StringUtils.hasText(stats)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(stats);
            double disM = node.path(STATS_MAX_DIS_IN_LAST_5S_M).asDouble(Double.NaN);
            if (Double.isNaN(disM) || disM <= 0) {
                return null;
            }
            double speed = disM / DIS_IN_LAST_5S_WINDOW_S * 3.6;
            return Math.min(speed, MAX_PLAUSIBLE_SPEED_KMH);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 历史包兜底：相邻点距离 / 时间差 的最大值（km/h，已剔除 GPS 跳变） */
    private double maxSpeedOf(List<MxfTrajectoryPoint> pts) {
        double max = 0;
        for (int i = 1; i < pts.size(); i++) {
            MxfTrajectoryPoint a = pts.get(i - 1);
            MxfTrajectoryPoint b = pts.get(i);
            if (a.getLat() == null || b.getLat() == null
                    || a.getLogTs() == null || b.getLogTs() == null) {
                continue;
            }
            long seconds = ChronoUnit.SECONDS.between(a.getLogTs(), b.getLogTs());
            if (seconds <= 0) {
                continue;
            }
            double km = haversineKm(a.getLat(), a.getLon(), b.getLat(), b.getLon());
            double speed = km / (seconds / 3600.0);
            if (isGpsJump(a, b, km)) {
                continue;
            }
            max = Math.max(max, speed);
        }
        return max;
    }

    /** 球面距离（km） */
    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private LocalDateTime minTime(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private LocalDateTime maxTime(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 触发事件轨迹草稿 */
    private record EventDraft(String eventName, String packetId, LocalDateTime firstTs,
                              List<MxfTrajectoryPoint> points) {
    }
}
