package com.isim.dims.service.impl;

import com.isim.dims.client.SigLIPClient;
import com.isim.dims.config.SigLIPConfig;
import com.isim.dims.dto.VideoSegmentMatch;
import com.isim.dims.dto.VideoSemanticSearchRequest;
import com.isim.dims.dto.VideoSemanticSearchResponse;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.ImageAssetMapper;
import com.isim.dims.mapper.VideoClipAssetMapper;
import com.isim.dims.service.VideoSemanticSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 视频语义检索服务实现（以文搜图 / 以图搜图）
 *
 * <p>完整流水线：
 * <pre>
 *   ① Query Embed   SigLIP2 编码文本/图片 → 768D
 *   ② Dual Recall   帧级 HNSW（image_asset）+ 片段级 HNSW（video_clip_asset）双路召回
 *   ③ Candidate     合并去重 → Top 100 候选池
 *   ④ Meta Filter   按 切片状态 / VIN / 数据来源 / topic / 时间窗 过滤候选
 *   ⑤ Temporal      邻帧时间衰减互证 → 时间连续性得分
 *   ⑥ Segment       按时间间隔把命中帧聚合成「时间区间」
 *   ⑦ Reranker      加权融合 frame/mean/temporal/clip/coverage → 排序
 *   ⑧ Top 20        输出 视频 + 时间区间 + 帧
 * </pre>
 * @feature: visualization
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoSemanticSearchServiceImpl implements VideoSemanticSearchService {

    /** 候选池上限（防护，避免超大请求拖垮向量库） */
    private static final int MAX_CANDIDATE_LIMIT = 500;
    /** 结果条数上限 */
    private static final int MAX_RESULT_LIMIT = 100;
    /** 时间连续性：邻帧互证窗口（纳秒） */
    private static final long TEMPORAL_WINDOW_NS = 30_000_000_000L;
    /** 时间连续性：指数衰减时间常数（纳秒） */
    private static final long TEMPORAL_TAU_NS = 10_000_000_000L;
    /** 覆盖度满分所需帧数 */
    private static final double COVERAGE_FULL_FRAMES = 3.0;
    /** Unix 纳秒时间戳合理下界（2000-01-01） */
    private static final long EPOCH_NS_MIN = 946_684_800_000_000_000L;
    /** Unix 纳秒时间戳合理上界（2100-01-01） */
    private static final long EPOCH_NS_MAX = 4_102_444_800_000_000_000L;

    private final SigLIPClient siglipClient;
    private final SigLIPConfig siglipConfig;
    private final ImageAssetMapper imageAssetMapper;
    private final VideoClipAssetMapper videoClipAssetMapper;
    private final DataSliceMapper dataSliceMapper;

    /**
     * 片段级召回可用性缓存：表未建时为 FALSE，避免每次请求都触发一次失败 SQL。
     * 非 final 字段，不参与 {@code @RequiredArgsConstructor}。
     */
    private volatile Boolean clipRecallAvailable;

    // =======================================================================
    // ①② 入口：以文搜图 / 以图搜图
    // =======================================================================

    @Override
    public VideoSemanticSearchResponse searchByText(VideoSemanticSearchRequest request) {
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            throw new RuntimeException("VALIDATION_FAILED: text is required");
        }
        requireEnabled();
        double[] query = siglipClient.embedText(request.getText().trim());
        return runPipeline(query, "TEXT", request);
    }

    @Override
    public VideoSemanticSearchResponse searchByImage(byte[] imageBytes, VideoSemanticSearchRequest request) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new RuntimeException("VALIDATION_FAILED: image file is empty");
        }
        requireEnabled();
        VideoSemanticSearchRequest req = request == null ? new VideoSemanticSearchRequest() : request;
        double[] query = siglipClient.embedImage(imageBytes);
        return runPipeline(query, "IMAGE", req);
    }

    // =======================================================================
    // ③ ~ ⑧ 流水线
    // =======================================================================

    private VideoSemanticSearchResponse runPipeline(double[] query, String queryType,
                                                    VideoSemanticSearchRequest req) {
        long t0 = System.currentTimeMillis();

        int resultLimit = resolveResultLimit(req.getLimit());
        int candidateLimit = resolveCandidateLimit(req.getCandidateLimit());
        String vec = toVectorLiteral(query);

        List<String> sliceIds = trimToNull(req.getSliceIds());
        List<String> topics = toTopicSafe(req.getTopics());
        Long startNs = req.getStartNs();
        Long endNs = req.getEndNs();
        boolean clipWanted = req.getIncludeClip() == null || Boolean.TRUE.equals(req.getIncludeClip());

        // ① 帧级召回（Frame HNSW）
        int frameLimit = Math.max(candidateLimit, resultLimit * 4);
        List<FrameCandidate> frames = new ArrayList<>();
        List<Map<String, Object>> frameRows =
                imageAssetMapper.searchByEmbeddingFiltered(vec, frameLimit, sliceIds, topics, startNs, endNs);
        if (frameRows != null) {
            for (Map<String, Object> row : frameRows) {
                FrameCandidate c = FrameCandidate.from(row);
                if (c != null) {
                    frames.add(c);
                }
            }
        }
        int frameCandidateCount = frames.size();

        // ② 片段级召回（Clip HNSW），表不存在时自动降级
        List<ClipCandidate> clips = Collections.emptyList();
        boolean clipUsed = false;
        if (clipWanted && isClipRecallAvailable()) {
            try {
                int clipLimit = Math.max(candidateLimit / 2, resultLimit * 2);
                List<Map<String, Object>> clipRows =
                        videoClipAssetMapper.searchByEmbedding(vec, clipLimit, sliceIds, topics, startNs, endNs);
                List<ClipCandidate> tmp = new ArrayList<>();
                if (clipRows != null) {
                    for (Map<String, Object> row : clipRows) {
                        ClipCandidate c = ClipCandidate.from(row);
                        if (c != null) {
                            tmp.add(c);
                        }
                    }
                }
                clips = tmp;
                clipUsed = true;
            } catch (Exception e) {
                clipRecallAvailable = Boolean.FALSE;
                log.warn("Clip-level recall unavailable, fallback to frame-only: {}", e.getMessage());
            }
        }

        // ③ 元数据过滤（切片级元数据来自主库 data_slice，仅对候选涉及的切片批量查询）
        Map<String, DataSlice> sliceMap = loadSlices(frames, clips);
        frames = filterFrames(frames, sliceMap, req, sliceIds);
        int filteredCandidateCount = frames.size();

        // ④ 时间连续性打分
        Map<String, List<FrameCandidate>> groups = groupBySliceTopic(frames);
        applyTemporalScore(groups);

        // ⑤ 时间区间聚合 + ⑥ 片段级得分
        long gapNs = secondsToNanos(req.getSegmentGapSec(), siglipConfig.getSearchSegmentGapSec());
        long maxSpanNs = secondsToNanos(req.getSegmentMaxSpanSec(), siglipConfig.getSearchSegmentMaxSpanSec());
        List<Segment> segments = buildSegments(groups, gapNs, maxSpanNs);
        applyClipScore(segments, clips);

        // ⑦ Reranker 加权融合
        Weights w = Weights.from(req);
        for (Segment seg : segments) {
            seg.score = w.frame * seg.frameScore
                    + w.mean * seg.meanScore
                    + w.temporal * seg.temporalScore
                    + w.clip * seg.clipScore
                    + w.coverage * seg.coverageScore;
        }
        Double minScore = req.getMinScore();
        if (minScore != null) {
            segments.removeIf(s -> s.frameScore < minScore);
        }
        segments.sort(Comparator.comparingDouble((Segment s) -> s.score).reversed());
        int segmentCount = segments.size();

        // ⑦-b 按 slice_id 去重：一个切片有 2~3 路摄像头，不去重时 Top N 会被
        //      少数强匹配切片重复占用名额（每个占 2~3 个），实际召回切片数只有 N/2~N/3。
        //      去重后每个切片只留最佳区间，Top N 即 N 个不同切片。
        boolean dedup = req.getDedupBySlice() == null
                ? siglipConfig.isSearchDedupBySlice()
                : Boolean.TRUE.equals(req.getDedupBySlice());
        List<Segment> ranked = dedup ? dedupBySlice(segments) : segments;
        int sliceCount = ranked.size();

        // ⑧ Top N 输出
        List<Segment> top = ranked.size() > resultLimit
                ? new ArrayList<>(ranked.subList(0, resultLimit)) : ranked;
        List<VideoSegmentMatch> matches = new ArrayList<>(top.size());
        for (Segment seg : top) {
            matches.add(toMatch(seg, sliceMap.get(seg.sliceId)));
        }

        return VideoSemanticSearchResponse.builder()
                .queryType(queryType)
                .resultCount(matches.size())
                .resultLimit(resultLimit)
                .frameCandidateCount(frameCandidateCount)
                .clipCandidateCount(clipUsed ? clips.size() : 0)
                .filteredCandidateCount(filteredCandidateCount)
                .segmentCount(segmentCount)
                .sliceCount(sliceCount)
                .dedupBySlice(dedup)
                .clipRecallEnabled(clipUsed)
                .costMs(System.currentTimeMillis() - t0)
                .segments(matches)
                .build();
    }

    // =======================================================================
    // 元数据过滤
    // =======================================================================

    private Map<String, DataSlice> loadSlices(List<FrameCandidate> frames, List<ClipCandidate> clips) {
        Set<String> ids = new LinkedHashSet<>();
        for (FrameCandidate c : frames) {
            ids.add(c.sliceId);
        }
        for (ClipCandidate c : clips) {
            ids.add(c.sliceId);
        }
        Map<String, DataSlice> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        try {
            List<DataSlice> slices = dataSliceMapper.selectBatchIds(new ArrayList<>(ids));
            if (slices != null) {
                for (DataSlice s : slices) {
                    map.put(s.getId(), s);
                }
            }
        } catch (Exception e) {
            // 主库查询失败时不阻断检索，退化为「不做切片元数据过滤」
            log.warn("Load slice metadata failed, skip metadata filtering: {}", e.getMessage());
        }
        return map;
    }

    private List<FrameCandidate> filterFrames(List<FrameCandidate> frames,
                                              Map<String, DataSlice> sliceMap,
                                              VideoSemanticSearchRequest req,
                                              List<String> sliceIdWhitelist) {
        List<String> statuses = toUpper(trimToNull(req.getStatuses()));
        List<String> sourceTypes = toUpper(trimToNull(req.getSourceTypes()));
        List<String> vins = toUpper(trimToNull(req.getVins()));
        LocalDateTime from = parseDateTime(req.getTimeFrom());
        LocalDateTime to = parseDateTime(req.getTimeTo());
        boolean needMeta = !statuses.isEmpty() || !sourceTypes.isEmpty() || !vins.isEmpty()
                || from != null || to != null;
        // 切片元数据整体加载失败时跳过过滤，避免误杀全部结果
        if (sliceMap.isEmpty()) {
            needMeta = false;
        }

        List<FrameCandidate> out = new ArrayList<>(frames.size());
        for (FrameCandidate c : frames) {
            if (sliceIdWhitelist != null && !sliceIdWhitelist.contains(c.sliceId)) {
                continue;
            }
            DataSlice slice = sliceMap.get(c.sliceId);
            if (sliceMap.containsKey(c.sliceId) && slice == null) {
                continue;
            }
            if (needMeta) {
                if (slice == null) {
                    continue;
                }
                if (!statuses.isEmpty() && !containsIgnoreCase(statuses, slice.getStatus())) {
                    continue;
                }
                if (!sourceTypes.isEmpty() && !containsIgnoreCase(sourceTypes, slice.getSourceType())) {
                    continue;
                }
                if (!vins.isEmpty() && !containsIgnoreCase(vins, slice.getVin())) {
                    continue;
                }
                LocalDateTime ts = slice.getTriggerTime() != null ? slice.getTriggerTime() : slice.getStartTs();
                if (from != null && (ts == null || ts.isBefore(from))) {
                    continue;
                }
                if (to != null && (ts == null || ts.isAfter(to))) {
                    continue;
                }
            }
            out.add(c);
        }
        return out;
    }

    // =======================================================================
    // 时间连续性打分 + 区间聚合 + Reranker
    // =======================================================================

    private Map<String, List<FrameCandidate>> groupBySliceTopic(List<FrameCandidate> frames) {
        Map<String, List<FrameCandidate>> groups = new LinkedHashMap<>();
        for (FrameCandidate c : frames) {
            groups.computeIfAbsent(c.sliceId + "|" + c.topicSafe, k -> new ArrayList<>()).add(c);
        }
        for (List<FrameCandidate> g : groups.values()) {
            g.sort(Comparator.comparingLong(f -> f.ts));
        }
        return groups;
    }

    /**
     * 时间连续性得分：真实场景的目标会在连续多帧中出现，孤立单帧多为误检。
     * 对窗口内的邻帧按时间距离做指数衰减加权，得分越高说明该帧被邻帧「互证」。
     */
    private void applyTemporalScore(Map<String, List<FrameCandidate>> groups) {
        for (List<FrameCandidate> g : groups.values()) {
            double[] support = new double[g.size()];
            double max = 0.0;
            for (int i = 0; i < g.size(); i++) {
                FrameCandidate a = g.get(i);
                double acc = 0.0;
                for (int j = 0; j < g.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    FrameCandidate b = g.get(j);
                    long dt = Math.abs(a.ts - b.ts);
                    if (dt <= TEMPORAL_WINDOW_NS) {
                        acc += b.score * Math.exp(-(double) dt / TEMPORAL_TAU_NS);
                    }
                }
                support[i] = acc;
                max = Math.max(max, acc);
            }
            for (int i = 0; i < g.size(); i++) {
                g.get(i).temporal = max > 0 ? support[i] / max : 0.0;
            }
        }
    }

    private List<Segment> buildSegments(Map<String, List<FrameCandidate>> groups, long gapNs, long maxSpanNs) {
        List<Segment> segments = new ArrayList<>();
        for (Map.Entry<String, List<FrameCandidate>> e : groups.entrySet()) {
            List<FrameCandidate> g = e.getValue();
            String[] key = e.getKey().split("\\|", 2);
            List<FrameCandidate> cur = new ArrayList<>();
            long segStart = 0L;
            for (FrameCandidate c : g) {
                if (cur.isEmpty()) {
                    cur.add(c);
                    segStart = c.ts;
                    continue;
                }
                long last = cur.get(cur.size() - 1).ts;
                if (c.ts - last <= gapNs && c.ts - segStart <= maxSpanNs) {
                    cur.add(c);
                } else {
                    segments.add(toSegment(key[0], key[1], cur));
                    cur = new ArrayList<>();
                    cur.add(c);
                    segStart = c.ts;
                }
            }
            if (!cur.isEmpty()) {
                segments.add(toSegment(key[0], key[1], cur));
            }
        }
        return segments;
    }

    /**
     * 按 slice_id 去重：同一切片只保留得分最高的区间（输入须已按 score 降序）。
     *
     * <p>一个切片通常有 2~3 路摄像头，每路各产出一个命中区间。不去重时，
     * 少数强匹配切片会重复占用 Top N 名额（每个占 2~3 个），导致实际召回的
     * 切片数只有 N/2~N/3。去重后 Top N 即 N 个互不相同的切片，
     * 且每个切片的排序依据是该切片的最佳区间得分。</p>
     *
     * @param sortedByScore 已按 Reranker 得分降序排列的区间列表
     * @return 每个切片一条，保持降序
     */
    private List<Segment> dedupBySlice(List<Segment> sortedByScore) {
        Map<String, Segment> best = new LinkedHashMap<>();
        for (Segment seg : sortedByScore) {
            // 输入已降序，同一 sliceId 首次出现的即为最佳区间
            best.putIfAbsent(seg.sliceId, seg);
        }
        return new ArrayList<>(best.values());
    }

    private Segment toSegment(String sliceId, String topicSafe, List<FrameCandidate> frames) {
        Segment seg = new Segment();
        seg.sliceId = sliceId;
        seg.topicSafe = topicSafe;
        seg.topic = frames.isEmpty() ? null : frames.get(0).topic;
        seg.frames = new ArrayList<>(frames);

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        double best = 0.0;
        double bestTemporal = 0.0;
        for (FrameCandidate c : frames) {
            min = Math.min(min, c.ts);
            max = Math.max(max, c.ts);
            best = Math.max(best, c.score);
            bestTemporal = Math.max(bestTemporal, c.temporal);
        }
        seg.startNs = min;
        seg.endNs = max;
        seg.frameScore = best;
        seg.temporalScore = bestTemporal;

        List<Double> sorted = new ArrayList<>();
        for (FrameCandidate c : frames) {
            sorted.add(c.score);
        }
        sorted.sort(Comparator.reverseOrder());
        double sum = 0.0;
        int n = Math.min(3, sorted.size());
        for (int i = 0; i < n; i++) {
            sum += sorted.get(i);
        }
        seg.meanScore = n > 0 ? sum / n : 0.0;
        seg.coverageScore = Math.min(1.0, frames.size() / COVERAGE_FULL_FRAMES);
        return seg;
    }

    /** 片段级得分：与区间有重叠的 Clip HNSW 命中取最高相似度 */
    private void applyClipScore(List<Segment> segments, List<ClipCandidate> clips) {
        if (clips.isEmpty()) {
            for (Segment seg : segments) {
                seg.clipScore = 0.0;
            }
            return;
        }
        for (Segment seg : segments) {
            double best = 0.0;
            for (ClipCandidate c : clips) {
                if (!seg.sliceId.equals(c.sliceId)) {
                    continue;
                }
                if (c.topicSafe != null && seg.topicSafe != null && !c.topicSafe.equals(seg.topicSafe)) {
                    continue;
                }
                if (c.startNs <= seg.endNs && c.endNs >= seg.startNs) {
                    best = Math.max(best, c.score);
                }
            }
            seg.clipScore = best;
        }
    }

    // =======================================================================
    // 结果装配
    // =======================================================================

    private VideoSegmentMatch toMatch(Segment seg, DataSlice slice) {
        List<VideoSegmentMatch.FrameHit> hits = new ArrayList<>();
        List<FrameCandidate> sorted = new ArrayList<>(seg.frames);
        sorted.sort(Comparator.<FrameCandidate>comparingDouble(f -> f.score).reversed());
        int topN = Math.max(1, siglipConfig.getSearchTopFrames());
        for (int i = 0; i < Math.min(topN, sorted.size()); i++) {
            FrameCandidate c = sorted.get(i);
            hits.add(VideoSegmentMatch.FrameHit.builder()
                    .ts(c.ts)
                    .tsIso(toIso(c.ts))
                    .fileName(c.fileName)
                    .url("/v1/data-slices/" + c.sliceId + "/video-images/" + c.topicSafe + "/" + c.fileName)
                    .score(round(c.score))
                    .build());
        }

        return VideoSegmentMatch.builder()
                .sliceId(seg.sliceId)
                .sliceName(slice == null ? null : slice.getName())
                .sliceStatus(slice == null ? null : slice.getStatus())
                .vin(slice == null ? null : slice.getVin())
                .sourceType(slice == null ? null : slice.getSourceType())
                .eventName(slice == null ? null : slice.getEventName())
                .topic(seg.topic)
                .topicSafe(seg.topicSafe)
                .videoUrl("/data/videos/" + seg.sliceId + "/" + seg.topicSafe + ".mp4")
                .startNs(seg.startNs)
                .endNs(seg.endNs)
                .startTime(toIso(seg.startNs))
                .endTime(toIso(seg.endNs))
                .durationSec(round((seg.endNs - seg.startNs) / 1_000_000_000.0))
                .score(round(seg.score))
                .frameScore(round(seg.frameScore))
                .meanScore(round(seg.meanScore))
                .temporalScore(round(seg.temporalScore))
                .clipScore(round(seg.clipScore))
                .coverageScore(round(seg.coverageScore))
                .frameCount(seg.frames.size())
                .frames(hits)
                .build();
    }

    // =======================================================================
    // 工具方法
    // =======================================================================

    private void requireEnabled() {
        if (!siglipClient.isEnabled()) {
            throw new RuntimeException("FEATURES_DISABLED: "
                    + "以文搜图/以图搜图需要 SigLIP2 特征库与 pgvector 向量库，"
                    + "请先部署 embedding 服务并开启 dims.siglip.enabled=true");
        }
    }

    private boolean isClipRecallAvailable() {
        Boolean flag = clipRecallAvailable;
        if (flag == null) {
            return true;
        }
        return Boolean.TRUE.equals(flag);
    }

    private int resolveResultLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return siglipConfig.getSearchResultLimit() > 0 ? siglipConfig.getSearchResultLimit() : 20;
        }
        return Math.min(limit, MAX_RESULT_LIMIT);
    }

    private int resolveCandidateLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return siglipConfig.getSearchCandidateLimit() > 0 ? siglipConfig.getSearchCandidateLimit() : 100;
        }
        return Math.min(limit, MAX_CANDIDATE_LIMIT);
    }

    private static long secondsToNanos(Integer overrideSec, int defaultSec) {
        int sec = overrideSec != null && overrideSec > 0 ? overrideSec
                : (defaultSec > 0 ? defaultSec : 10);
        return sec * 1_000_000_000L;
    }

    private static String toVectorLiteral(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(v[i]);
        }
        return sb.append("]").toString();
    }

    private static List<String> trimToNull(List<String> src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String s : src) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> toUpper(List<String> src) {
        if (src == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(src.size());
        for (String s : src) {
            out.add(s.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> upperList, String value) {
        return value != null && upperList.contains(value.toUpperCase(Locale.ROOT));
    }

    /** topic（如 /sensor/camera/front_far/video）→ topic_safe（_sensor_camera_front_far_video） */
    private static List<String> toTopicSafe(List<String> topics) {
        List<String> src = trimToNull(topics);
        if (src == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String t : src) {
            String safe = t.replace("/", "_").replace(".", "_");
            if (!out.contains(safe)) {
                out.add(safe);
            }
        }
        return out;
    }

    private static LocalDateTime parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        try {
            if (s.length() == 10) {
                return LocalDateTime.of(LocalDate.parse(s), LocalTime.MIN);
            }
            return LocalDateTime.parse(s);
        } catch (Exception ignored) {
            // 尝试带时区偏移/时区的写法
        }
        try {
            // 前端按北京时间回传（...+08:00），库里时间一律 UTC → 换算到同一时刻的 UTC 墙钟后再比较
            return OffsetDateTime.parse(s)
                    .withOffsetSameInstant(java.time.ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (Exception ignored) {
            // 尝试 Instant（...Z）
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(s), java.time.ZoneOffset.UTC);
        } catch (Exception e) {
            log.warn("Ignore unparsable time filter: {}", s);
            return null;
        }
    }

    /** bag 纳秒时间戳 → ISO-8601 UTC；非 Unix 纳秒量级（相对时间戳）时返回 null */
    private static String toIso(long ns) {
        if (ns < EPOCH_NS_MIN || ns > EPOCH_NS_MAX) {
            return null;
        }
        return Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L).toString();
    }

    private static Double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static long toLong(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double toDouble(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    // =======================================================================
    // 内部数据结构
    // =======================================================================

    /** 帧级候选 */
    private static final class FrameCandidate {
        private String sliceId;
        private String topic;
        private String topicSafe;
        private String fileName;
        private long ts;
        private double score;
        private double temporal;

        static FrameCandidate from(Map<String, Object> row) {
            String sliceId = str(row, "slice_id");
            String topicSafe = str(row, "topic_safe");
            if (sliceId == null || sliceId.isBlank() || topicSafe == null || topicSafe.isBlank()) {
                return null;
            }
            FrameCandidate c = new FrameCandidate();
            c.sliceId = sliceId;
            c.topic = str(row, "topic");
            c.topicSafe = topicSafe;
            c.fileName = str(row, "file_name");
            c.ts = toLong(row, "ts");
            c.score = toDouble(row, "score");
            return c;
        }
    }

    /** 片段级候选 */
    private static final class ClipCandidate {
        private String sliceId;
        private String topicSafe;
        private long startNs;
        private long endNs;
        private double score;

        static ClipCandidate from(Map<String, Object> row) {
            String sliceId = str(row, "slice_id");
            if (sliceId == null || sliceId.isBlank()) {
                return null;
            }
            ClipCandidate c = new ClipCandidate();
            c.sliceId = sliceId;
            c.topicSafe = str(row, "topic_safe");
            c.startNs = toLong(row, "start_ns");
            c.endNs = toLong(row, "end_ns");
            c.score = toDouble(row, "score");
            return c;
        }
    }

    /** 聚合后的时间区间 */
    private static final class Segment {
        private String sliceId;
        private String topic;
        private String topicSafe;
        private long startNs;
        private long endNs;
        private List<FrameCandidate> frames;
        private double frameScore;
        private double meanScore;
        private double temporalScore;
        private double clipScore;
        private double coverageScore;
        private double score;
    }

    /** Reranker 权重 */
    private static final class Weights {
        private final double frame;
        private final double mean;
        private final double temporal;
        private final double clip;
        private final double coverage;

        private Weights(double frame, double mean, double temporal, double clip, double coverage) {
            this.frame = frame;
            this.mean = mean;
            this.temporal = temporal;
            this.clip = clip;
            this.coverage = coverage;
        }

        static Weights from(VideoSemanticSearchRequest req) {
            double frame = pick(req.getWeightFrame(), 0.40);
            double mean = pick(req.getWeightMean(), 0.20);
            double temporal = pick(req.getWeightTemporal(), 0.15);
            double clip = pick(req.getWeightClip(), 0.15);
            double coverage = pick(req.getWeightCoverage(), 0.10);
            return new Weights(frame, mean, temporal, clip, coverage);
        }

        private static double pick(Double v, double def) {
            return v == null || v.isNaN() || v < 0 ? def : v;
        }
    }
}
