package com.isim.dims.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.isim.dims.dto.TrajectoryQueryResult;
import com.isim.dims.dto.TrajectoryResponse;
import com.isim.dims.dto.VehicleTrackResponse;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.ReplayService;
import com.isim.dims.service.TrajectoryService;
import com.isim.dims.service.VehicleTrackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 看板「车辆位置地图」实现。
 *
 * <h3>取数链路</h3>
 * <ol>
 *   <li>data_slice 按 vin 分组，取 trigger_time 在最近 N 小时内最新的一条
 *       READY 切片（{@link DataSliceMapper#selectLatestSlicesPerVin}）。</li>
 *   <li>优先：读该切片回放产物 rosdata.json，取 {@code egoPoseMessages}
 *       （extract_replay.py 从 /mla/egopose 提取的经纬度轨迹，度制 WGS-84）。</li>
 *   <li>兜底：egopose 无经纬度（部分 bag 只有 boot 系 x/y/yaw）或产物缺失时，
 *       改用切片 GPS 轨迹缓存（/sensor/gnss_rtk 等提取，仅取 READY 结果）。</li>
 *   <li>抽稀到 {@link #MAX_POINTS} 点，末点即主车最后位置。</li>
 * </ol>
 *
 * <h3>缓存</h3>
 * <p>看板 30s 轮询 + rosdata.json 体积较大，故按 sliceId 缓存抽稀后的轨迹点
 * （TTL {@link #CACHE_TTL_MS}，容量 {@link #CACHE_MAX_ENTRIES}）。缓存的是点位
 * 而非整份 JSON，避免重复解析。</p>
 *
 * <h3>软失败</h3>
 * <p>单个切片读取失败（产物缺失 / 已归档 / JSON 损坏 / 无定位字段）不影响其他
 * 车辆：该车 points 为空返回，由前端提示「无定位数据」。</p>
 *
 * @feature: mxf
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleTrackServiceImpl implements VehicleTrackService {

    /** 默认时间窗口（小时）——需求：触发时间在 24 小时内的切片 */
    private static final int DEFAULT_HOURS = 24;
    private static final int MAX_HOURS = 168;
    /** 默认/最大车辆数（地图可读性 + 单请求开销上限） */
    private static final int DEFAULT_MAX_VEHICLES = 50;
    private static final int MAX_VEHICLES = 50;
    /** 单车最多渲染点数（抽稀，保留首尾） */
    private static final int MAX_POINTS = 300;
    /** 轨迹数据来源标记 */
    private static final String SOURCE_EGO_POSE = "ego_pose";
    private static final String SOURCE_GPS_TRAJECTORY = "gps_trajectory";
    private static final String SOURCE_NONE = "none";
    /** 轨迹点缓存 TTL / 容量 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int CACHE_MAX_ENTRIES = 200;

    /**
     * 出参时间口径：北京时间（东八区）。DB 时间列按 UTC 落库（契约 v1.3.0），
     * 查询窗口仍用 UTC 比较，响应里的 triggerTime / generatedAt 统一转北京时间输出，
     * 前端不再依赖浏览器时区。
     */
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BEIJING_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSliceMapper dataSliceMapper;
    private final ReplayService replayService;
    private final TrajectoryService trajectoryService;

    private final Map<String, CachedTrack> cache = new ConcurrentHashMap<>();

    @Override
    public VehicleTrackResponse getVehicleTracks(int hours, int maxVehicles) {
//        int windowHours = hours <= 0 ? DEFAULT_HOURS : Math.min(hours, MAX_HOURS);
        int windowHours = hours <= 0 ? DEFAULT_HOURS : Math.max(hours, MAX_HOURS);
        int vehicleLimit = maxVehicles <= 0 ? DEFAULT_MAX_VEHICLES
                : Math.min(maxVehicles, MAX_VEHICLES);

        LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minusHours(windowHours);
        List<DataSlice> slices = dataSliceMapper.selectLatestSlicesPerVin(from, vehicleLimit);

        List<VehicleTrackResponse.VehicleTrack> tracks = new ArrayList<>();
        for (DataSlice slice : slices) {
            CachedTrack loaded = loadTrack(slice.getId());
            List<VehicleTrackResponse.TrackPoint> points = loaded.points();
            VehicleTrackResponse.TrackPoint last = points.isEmpty()
                    ? null : points.get(points.size() - 1);
            tracks.add(VehicleTrackResponse.VehicleTrack.builder()
                    .vin(slice.getVin())
                    .sliceId(slice.getId())
                    .sliceName(slice.getName())
                    .triggerTime(formatBeijing(slice.getTriggerTime()))
                    .sourceType(slice.getSourceType())
                    .eventName(slice.getEventName())
                    .pointCount(points.size())
                    .dataSource(loaded.source())
                    .lastLat(last == null ? null : last.getLat())
                    .lastLon(last == null ? null : last.getLon())
                    .points(points)
                    .build());
        }

        log.info("[VehicleTrack] hours={}, vehicles={}（有定位 {} 辆）",
                windowHours, tracks.size(), tracks.stream().filter(t -> t.getPointCount() > 0).count());

        return VehicleTrackResponse.builder()
                .hours(windowHours)
                .generatedAt(LocalDateTime.now(BEIJING_ZONE).format(BEIJING_TS))
                .vehicleCount(tracks.size())
                .vehicles(tracks)
                .build();
    }

    // ========================================================================
    // 轨迹读取
    // ========================================================================

    /**
     * 读取切片轨迹（带缓存）：
     * ① 回放产物 rosdata.json 的 egoPoseMessages（/mla/egopose 经纬度）；
     * ② 兜底：切片 GPS 轨迹产物（/sensor/gnss_rtk 等，仅取 READY 缓存，
     *    未提取过的会触发一次异步提取，自愈后下次命中）。
     * 无定位数据返回空点集（source = none）。
     */
    private CachedTrack loadTrack(String sliceId) {
        CachedTrack cached = cache.get(sliceId);
        if (cached != null && !cached.expired()) {
            return cached;
        }

        List<VehicleTrackResponse.TrackPoint> points = readEgoPosePoints(sliceId);
        String source = points.isEmpty() ? SOURCE_NONE : SOURCE_EGO_POSE;
        if (points.isEmpty()) {
            points = readGpsTrajectoryPoints(sliceId);
            source = points.isEmpty() ? SOURCE_NONE : SOURCE_GPS_TRAJECTORY;
        }

        if (cache.size() >= CACHE_MAX_ENTRIES) {
            // 简单容量控制：清掉过期项，仍满则整体清空（看板场景可接受）
            cache.entrySet().removeIf(e -> e.getValue().expired());
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.clear();
            }
        }
        CachedTrack loaded = new CachedTrack(points, source,
                System.currentTimeMillis() + CACHE_TTL_MS);
        cache.put(sliceId, loaded);
        return loaded;
    }

    /**
     * 从回放产物 rosdata.json 提取 egoPoseMessages 的经纬度点（软失败 → 空列表）。
     *
     * <p>注意：部分 bag 的 /mla/egopose 只有 boot 系局部坐标（x/y/yaw）而无经纬度
     * （Excel 口径：egopose 为「boot 系下的坐标位置」），此时全部点因缺 lat/lon 被
     * 丢弃 → 返回空列表，由 {@link #loadTrack} 走 GPS 轨迹兜底。</p>
     */
    private List<VehicleTrackResponse.TrackPoint> readEgoPosePoints(String sliceId) {
        try {
            JsonNode root = replayService.getRosData(sliceId);
            JsonNode messages = root.path("egoPoseMessages");
            if (!messages.isArray()) {
                return Collections.emptyList();
            }
            List<VehicleTrackResponse.TrackPoint> points = new ArrayList<>();
            for (JsonNode node : messages) {
                Double lat = number(node, "lat");
                Double lon = number(node, "lon");
                if (lat != null && lon != null) {
                    points.add(VehicleTrackResponse.TrackPoint.builder().lat(lat).lon(lon).build());
                }
            }
            return decimate(points, MAX_POINTS);
        } catch (Exception e) {
            log.debug("[VehicleTrack] egoPoseMessages 不可用: sliceId={}, err={}", sliceId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * GPS 轨迹兜底：读 gps_trajectory 缓存（extract_gps_trajectory.py 产物）。
     * 仅消费 READY 结果；其余状态（含 NOT_EXTRACTED 已触发的异步提取）返回空。
     */
    private List<VehicleTrackResponse.TrackPoint> readGpsTrajectoryPoints(String sliceId) {
        try {
            TrajectoryQueryResult result = trajectoryService.getTrajectory(sliceId);
            if (!"READY".equals(result.getStatus()) || result.getTrajectory() == null) {
                return Collections.emptyList();
            }
            List<VehicleTrackResponse.TrackPoint> points = new ArrayList<>();
            for (TrajectoryResponse.TrajectoryPoint p : result.getTrajectory().getTrajectory()) {
                if (p != null && p.getLat() != null && p.getLon() != null) {
                    points.add(VehicleTrackResponse.TrackPoint.builder()
                            .lat(p.getLat()).lon(p.getLon()).build());
                }
            }
            return decimate(points, MAX_POINTS);
        } catch (Exception e) {
            log.debug("[VehicleTrack] GPS 轨迹兜底不可用: sliceId={}, err={}", sliceId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 均匀抽稀到 limit 点（保留首尾） */
    private static List<VehicleTrackResponse.TrackPoint> decimate(
            List<VehicleTrackResponse.TrackPoint> points, int limit) {
        if (limit <= 0 || points.size() <= limit) {
            return points;
        }
        List<VehicleTrackResponse.TrackPoint> out = new ArrayList<>(limit);
        if (limit == 1) {
            out.add(points.get(0));
            return out;
        }
        double step = (points.size() - 1) / (double) (limit - 1);
        for (int i = 0; i < limit; i++) {
            out.add(points.get(Math.min((int) Math.round(i * step), points.size() - 1)));
        }
        return out;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    /** DB 时间列（UTC 口径）→ 北京时间字符串（yyyy-MM-dd HH:mm:ss），入参为空返回 null */
    private static String formatBeijing(LocalDateTime utc) {
        if (utc == null) {
            return null;
        }
        return utc.toInstant(ZoneOffset.UTC).atZone(BEIJING_ZONE).format(BEIJING_TS);
    }

    /** 轨迹点缓存项（points 不可变 + 来源 + 过期时间） */
    private record CachedTrack(List<VehicleTrackResponse.TrackPoint> points, String source,
                               long expiresAtMs) {

        boolean expired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }
}