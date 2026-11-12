package com.isim.dims.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.*;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.schedule.DownloadHirain835Schedule;
import com.isim.dims.security.RequireSliceDelete;
import com.isim.dims.security.RequireSliceReimport;
import com.isim.dims.security.RequireSliceWrite;
import com.isim.dims.service.BosDirectoryRestoreService;
import com.isim.dims.service.DataSliceService;
import com.isim.dims.service.FisheyePreviewService;
import com.isim.dims.service.LocalDirectoryImportService;
import com.isim.dims.service.SliceLoadService;
import com.isim.dims.service.VideoSemanticSearchService;
import com.isim.dims.service.impl.FilterOptionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * 数据切片 Controller
 * <p>
 * 负责 ROS1 bag 文件上传导入、多条件检索、详情查看和删除操作。
 * 对应 OpenAPI paths:
 * POST   /v1/data-slices
 * GET    /v1/data-slices
 * GET    /v1/data-slices/filter-options
 * GET    /v1/data-slices/{sliceId}
 * DELETE /v1/data-slices/{sliceId}
 *
 * @feature: slice
 */
@Slf4j
@RestController
@RequestMapping("/v1/data-slices")
@RequiredArgsConstructor
public class DataSliceController {

    private final DataSliceService dataSliceService;
    private final LocalDirectoryImportService localDirectoryImportService;
    private final BosDirectoryRestoreService bosDirectoryRestoreService;
    private final ObjectMapper objectMapper;
    private final DataSliceMapper dataSliceMapper;
    private final FilterOptionProvider filterOptionProvider;
    private final SliceLoadService sliceLoadService;
    private final FisheyePreviewService fisheyePreviewService;
    private final VideoSemanticSearchService videoSemanticSearchService;
    private final DownloadHirain835Schedule downloadHirain835Schedule;

    /**
     * 并发任务数上限：最多允许 30 个处于活跃导入状态的数据切片
     */
    private static final int MAX_CONCURRENT_TASKS = 30;

    /**
     * 本地目录导入已知错误码（对应 400；其余异常 → 500 INTERNAL_ERROR）
     *
     * <p>对齐 dims.openapi.yaml v1.4.0：strict 校验失败统一为 VALIDATION_FAILED
     * （具体原因在 message 中）；MD5_NOT_MAPPED / INVALID_SESSION / ALREADY_IMPORTED /
     * MFBAG_NOT_FOUND 仅出现在 skipped 明细，不再作为 400 错误码；
     * SCAN_FAILED 属硬错误 → 500。PATH_NOT_ALLOWED 为可选加固预留。</p>
     */
    private static final Set<String> KNOWN_IMPORT_ERROR_CODES = Set.of(
            "DIRECTORY_NOT_FOUND", "DIRECTORY_NOT_DIRECTORY", "DIRECTORY_NOT_READABLE",
            "EMPTY_DIRECTORY", "PATH_NOT_ALLOWED", "VALIDATION_FAILED",
            "MAX_CONCURRENT_TASKS_EXCEEDED", "MAX_CONCURRENT_TASKS_LOCKED"
    );

    /**
     * 数据来源提供方白名单（上传接口 source 参数）
     */
    private static final Set<String> ALLOWED_SOURCES = Set.of("MMT", "ZYT", "Hirain");

    /**
     * 上传 ROS1 bag 文件或 CFDI tar.gz 数据包，创建数据切片
     * <p>
     * POST /v1/data-slices
     * multipart/form-data
     * <p>
     * 文件扩展名决定处理方式：
     * .bag     → 走标准解析流水线
     * .tar.gz  → 走 CFDI 转换+解析流水线
     *
     * @param file   ROS1 bag 文件（.bag）或 CFDI 数据包（.tar.gz）
     * @param name   切片名称（可选，默认取文件名不含扩展名）
     * @param source 数据来源提供方（必填）：MMT | ZYT | Hirain
     * @return 202 + { slice_id, task_id, status }；source 缺失/非法 → 400 VALIDATION_FAILED
     */
    @RequireSliceWrite
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "source", required = false) String source) {
        log.info("upload request: fileName={}, name={}, source={}", file.getOriginalFilename(), name, source);
        if (source == null || !ALLOWED_SOURCES.contains(source)) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "source must be one of MMT/ZYT/Hirain"));
        }
        try {
            Map<String, String> result = dataSliceService.upload(file, name, source);
            return ResponseEntity.status(202).body(result);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            log.error("upload failed: {}", msg, e);
            if (msg != null && msg.startsWith("UPLOAD_FAILED:")) {
                return ResponseEntity.badRequest()
                        .body(ErrorResponse.of("UPLOAD_FAILED", msg));
            }
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("UPLOAD_FAILED", msg));
        } catch (Exception e) {
            log.error("upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("UPLOAD_FAILED", e.getMessage()));
        }
    }

    /**
     * 本地目录导入：扫描服务器本地目录批量创建导入任务
     * <p>
     * POST /v1/data-slices/directory-import
     * application/json
     * <p>
     * 扫描目录下 MD5 命名的子目录（{md5_32hex}/{md5后8位}_{时间戳}_public），
     * 经 vin_md5_mapping 反查 VIN 后批量创建 DataSlice（source_type=前端传参的
     * 数据来源提供方）+ IngestionTask，并异步派发 CFDI 同款转换流水线。
     *
     * @param request { directory(必填), name(可选前缀), source(必填 MMT/ZYT/Hirain), strict(默认 false) }
     * @param traceId 链路追踪 ID
     * @return 202 + 导入摘要（imported / skipped）；strict 校验失败 → 400
     */
//    @RequireSliceWrite
    @PostMapping("/directory-import")
    public ResponseEntity<?> importFromDirectory(
            @RequestBody DirectoryImportRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("directory-import request: dir={}, name={}, strict={}, traceId={}",
                request != null ? request.getDirectory() : null,
                request != null ? request.getName() : null,
                request != null && request.isStrict(),
                traceId);
        try {
            DirectoryImportResponse response = localDirectoryImportService.importDirectory(request);
            return ResponseEntity.status(202).body(response);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null) {
                String code = msg.contains(":") ? msg.substring(0, msg.indexOf(':')) : msg;
                if (KNOWN_IMPORT_ERROR_CODES.contains(code)) {
                    log.warn("directory-import rejected: code={}, msg={}", code, msg);
                    return ResponseEntity.badRequest()
                            .body(ErrorResponse.of(code, msg, traceId));
                }
            }
            log.error("directory-import failed: msg={}", msg, e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("INTERNAL_ERROR", msg, traceId));
        } catch (Exception e) {
            log.error("directory-import failed: msg={}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("INTERNAL_ERROR", e.getMessage(), traceId));
        }
    }

    /**
     * meta-only 本地目录导入：仅解析 meta 建切片（status=META_READY），不跑完整导入流水线
     * <p>
     * POST /v1/data-slices/directory-import-meta-only
     * application/json
     * <p>
     * 扫描目录下 MD5 命名的子目录（{md5_32hex}/{md5后8位}_{时间戳}_public），
     * 仅解析 meta 创建 DataSlice（META_READY，含事件统计元信息），
     * <b>不校验 mfbag、不创建 IngestionTask、不跑转换流水线</b>，
     * 并异步将前视鱼眼 h265 转码为可播放预览 mp4。
     * 切片记录 import_bos_key = bosKeyPrefix/{sessionDir}，
     * 用户在数据浏览器点「加载」时按需全量拉取并复用完整导入流水线。
     *
     * @param request { directory(必填), source(必填 MMT/ZYT/Hirain), bosKeyPrefix(必填) }
     * @param traceId 链路追踪 ID
     * @return 202 + 导入摘要（imported=META_READY 切片，skipped=无 meta 或已导入的会话）；校验失败 → 400
     */
    @PostMapping("/directory-import-meta-only")
    public ResponseEntity<?> importFromDirectoryMetaOnly(
            @RequestBody DirectoryImportRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("directory-import-meta-only request: dir={}, bosKeyPrefix={}, traceId={}",
                request != null ? request.getDirectory() : null,
                request != null ? request.getBosKeyPrefix() : null,
                traceId);
        try {
            DirectoryImportResponse response = localDirectoryImportService.importDirectoryMetaOnly(request);
            return ResponseEntity.status(202).body(response);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null) {
                String code = msg.contains(":") ? msg.substring(0, msg.indexOf(':')) : msg;
                if (KNOWN_IMPORT_ERROR_CODES.contains(code)) {
                    log.warn("directory-import-meta-only rejected: code={}, msg={}", code, msg);
                    return ResponseEntity.badRequest()
                            .body(ErrorResponse.of(code, msg, traceId));
                }
            }
            log.error("directory-import-meta-only failed: msg={}", msg, e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("INTERNAL_ERROR", msg, traceId));
        } catch (Exception e) {
            log.error("directory-import-meta-only failed: msg={}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("INTERNAL_ERROR", e.getMessage(), traceId));
        }
    }

    /* ==================== 按需加载（meta-only 切片：拉全量 + 复用导入流水线） ==================== */

    /**
     * 触发按需加载：按切片记录的 import_bos_key 从 BOS 全量拉取会话文件，
     * 下载完成后复用本地目录导入流水线（转换 → 解析 → 打标 → READY）。
     * <p>
     * POST /v1/data-slices/{sliceId}/load
     *
     * @param sliceId 切片 UUID（须为 META_READY 或加载失败的 ERROR）
     * @return 202 + { slice_id, task_id, status, estimated_seconds, total_bytes, ... }
     */
//    @RequireSliceWrite
    @PostMapping("/{sliceId}/load")
    public ResponseEntity<?> startLoad(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("startLoad request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            Map<String, Object> result = sliceLoadService.startLoad(sliceId);
            return ResponseEntity.status(202).body(result);
        } catch (IllegalStateException e) {
            String code = resolveErrorCode(e.getMessage(), "LOAD_STATE_INVALID");
            log.warn("startLoad rejected: sliceId={}, code={}, msg={}", sliceId, code, e.getMessage());
            HttpStatus httpStatus = "SLICE_NOT_FOUND".equals(code) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(httpStatus)
                    .body(ErrorResponse.of(code, e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("startLoad failed: sliceId={}, err={}", sliceId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("LOAD_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 查询按需加载进度（前端「加载数据中，预计剩余 …」轮询）
     * <p>
     * GET /v1/data-slices/{sliceId}/load/status
     *
     * @param sliceId 切片 UUID
     * @return 200 + { slice_id, status, stage, progress, downloaded_bytes, total_bytes,
     * estimated_seconds, elapsed_seconds, remaining_seconds, task_id, error_msg }
     */
    @GetMapping("/{sliceId}/load/status")
    public ResponseEntity<?> getLoadStatus(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            return ResponseEntity.ok(sliceLoadService.getLoadStatus(sliceId));
        } catch (IllegalStateException e) {
            String code = resolveErrorCode(e.getMessage(), "SLICE_NOT_FOUND");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of(code, e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("getLoadStatus failed: sliceId={}, err={}", sliceId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("LOAD_STATUS_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 前视鱼眼预览：未加载（META_READY / LOADING）的切片只落了 meta + 前视鱼眼 h265，
     * 由本接口告知预览 mp4 是否已生成及可播放地址（浏览器直接交给
     * {@code GET /data/videos/{sliceId}/{filename}} 流式端点播放）。
     * <p>
     * GET /v1/data-slices/{sliceId}/preview
     *
     * @param sliceId 切片 UUID
     * @return 200 + { slice_id, has_preview, url, status }
     */
    @GetMapping("/{sliceId}/preview")
    public ResponseEntity<?> getPreview(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            boolean hasPreview = fisheyePreviewService.hasPreview(sliceId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("slice_id", sliceId);
            result.put("has_preview", hasPreview);
            result.put("url", "/data/videos/" + sliceId + "/" + FisheyePreviewService.PREVIEW_VIDEO_NAME);
            result.put("status", "READY");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("getPreview failed: sliceId={}, err={}", sliceId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("PREVIEW_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 从「CODE: message」形式的异常消息中取出错误码（无冒号时回退默认码）
     */
    private static String resolveErrorCode(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        int idx = message.indexOf(':');
        String code = idx > 0 ? message.substring(0, idx) : message;
        return code.isBlank() ? fallback : code;
    }

    /**
     * 多条件检索数据切片（分页）
     * <p>
     * GET /v1/data-slices
     * 客户端按 OpenAPI 契约发送 snake_case 查询参数。
     * 通过 {@code @RequestParam Map<String,String>} 接收全部参数，
     * 再由 Jackson ObjectMapper（已配置为 SNAKE_CASE）将 snake_case key
     * 映射到 SliceSearchRequest 的 camelCase 字段。
     *
     * @param params 原始查询参数 Map（snake_case keys）
     * @return 200 + 分页的切片列表
     */
    @GetMapping
    public ResponseEntity<?> search(
            @RequestParam Map<String, String> params) {
        log.info("search request: params={}", params);
        try {
            // 过滤空值并转换 snake_case → camelCase
            Map<String, String> filtered = new HashMap<>();
            if (params != null) {
                params.forEach((k, v) -> {
                    if (v != null && !v.isEmpty()) {
                        filtered.put(k, v);
                    }
                });
            }
            SliceSearchRequest request = objectMapper.convertValue(filtered, SliceSearchRequest.class);
            request.setStartRow((request.getPage() - 1) * request.getPageSize()); // 前端 page 从 1 开始，Service 层从 0 开始
            SliceListResponse response = dataSliceService.search(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("search failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SEARCH_FAILED", e.getMessage()));
        }
    }

    /**
     * 触发类型（event_name）下拉选项：按筛选器分组
     * <p>
     * GET /v1/data-slices/filter-options
     * <p>
     * 数据源与智驾事件看板的「筛选器」下拉完全一致 —— 同一份
     * {@code camera_event_config.json} 的 {@code events.*.filter}（见
     * {@link FilterOptionProvider}），但只返回选项本身：纯内存读取，不查库、不算趋势。
     * <p>
     * 字段口径见 {@link SliceFilterOptionsResponse}：{@code filter} 是筛选器名（分组标题），
     * {@code event_names} 是请求实际要发送的英文事件名，{@code titles} 是与之一一对应的
     * 中文标题。⚠️ 一个筛选器可能映射多个事件（如 {@code highbeam_on_filter} →
     * 高中光 + 中远光），前端需按事件逐项渲染。
     * <p>
     * 路径是字面量，Spring 的 PathPattern 会优先匹配它而不是 {@code /{sliceId}}（详情端点）。
     *
     * @return 200 + { filters: [ { filter, event_names, titles } ] }；配置缺失 → filters 为 []
     */
    @GetMapping("/filter-options")
    public ResponseEntity<SliceFilterOptionsResponse> filterOptions() {
        List<FilterOption> filters = filterOptionProvider.list();
        log.info("filter-options request: {} filters", filters.size());
        return ResponseEntity.ok(SliceFilterOptionsResponse.builder().filters(filters).build());
    }

    /* ==================== 以文搜图 / 以图搜图（视频片段级语义检索） ==================== */

    /**
     * 以文搜图（视频片段级）
     * <p>
     * POST /v1/data-slices/search/by-text
     * application/json
     * <p>
     * 检索流程：
     * <pre>
     *   MP4 → FFmpeg → Frame(帧) + Clip(片段) → SigLIP2 → 768D → pgvector
     *       → Frame HNSW + Clip HNSW 双路召回 → Top 100 候选池
     *       → Metadata Filter + Temporal Score → Reranker → Top 20
     * </pre>
     * 输出「视频 + 时间区间 + 帧」：每个命中即一个可跳转播放的视频片段区间，
     * 而不是零散的单帧图片。
     * <p>
     * 请求体示例：
     * <pre>
     * {
     *   "text": "A pedestrian crossing the road",
     *   "limit": 20,
     *   "candidate_limit": 100,
     *   "topics": ["/sensor/camera/front_far/video"],
     *   "statuses": ["READY"],
     *   "vins": ["LSVAA1234567890"],
     *   "time_from": "2026-07-01T00:00:00",
     *   "time_to": "2026-07-31T23:59:59",
     *   "segment_gap_sec": 10,
     *   "min_score": 0.2
     * }
     * </pre>
     * 路径是字面量，Spring 的 PathPattern 会优先匹配它而不是 {@code /{sliceId}}（详情端点）。
     *
     * @param request 检索请求（text 必填，其余可选）
     * @param traceId 链路追踪 ID
     * @return 200 + { query_type, result_count, segment_count, clip_recall_enabled, cost_ms, segments: [...] }
     */
    @PostMapping("/search/by-text")
    public ResponseEntity<?> searchVideoByText(
            @RequestBody(required = false) VideoSemanticSearchRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("video search by-text: text={}, traceId={}",
                request == null ? null : request.getText(), traceId);
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "text is required", traceId));
        }
        try {
            return ResponseEntity.ok(videoSemanticSearchService.searchByText(request));
        } catch (Exception e) {
            return semanticSearchError(e, traceId);
        }
    }

    /**
     * 以图搜图（视频片段级）
     * <p>
     * POST /v1/data-slices/search/by-image
     * multipart/form-data
     * <p>
     * 与 {@code /search/by-text} 走同一条流水线，仅查询特征来源不同：
     * 上传图片先经 SigLIP2 编码为 768D 向量，再走双路召回 + 元数据过滤 +
     * 时间连续性打分 + Reranker，最终输出「视频 + 时间区间 + 帧」。
     * <p>
     * 检索参数（除 file 外的可选过滤条件）通过 {@code payload} 表单字段传入 JSON 字符串，
     * 字段与 {@link VideoSemanticSearchRequest} 一致（snake_case）。
     *
     * @param file    查询图片（JPEG/PNG）
     * @param payload 可选检索参数 JSON 字符串
     * @param traceId 链路追踪 ID
     * @return 200 + { query_type, result_count, segment_count, clip_recall_enabled, cost_ms, segments: [...] }
     */
    @PostMapping(value = "/search/by-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> searchVideoByImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "payload", required = false) String payload,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("video search by-image: size={}, payload={}, traceId={}",
                file == null ? null : file.getSize(), payload, traceId);
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "file is required", traceId));
        }
        VideoSemanticSearchRequest request;
        try {
            request = payload == null || payload.isBlank()
                    ? new VideoSemanticSearchRequest()
                    : objectMapper.readValue(payload, VideoSemanticSearchRequest.class);
        } catch (IOException e) {
            log.warn("video search by-image: invalid payload JSON: {}", payload);
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "payload is not valid JSON", traceId));
        }
        try {
            return ResponseEntity.ok(videoSemanticSearchService.searchByImage(file.getBytes(), request));
        } catch (IOException e) {
            log.error("Read uploaded image failed", e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("READ_FAILED", "读取上传图片失败", traceId));
        } catch (Exception e) {
            return semanticSearchError(e, traceId);
        }
    }

    /**
     * 语义检索统一错误处理：FEATURES_DISABLED → 503，VALIDATION_FAILED → 400，其余 → 500
     */
    private ResponseEntity<?> semanticSearchError(Exception e, String traceId) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (msg.startsWith("FEATURES_DISABLED")) {
            return ResponseEntity.status(503)
                    .body(ErrorResponse.of("FEATURES_DISABLED", msg, traceId));
        }
        log.error("Video semantic search failed: {}", msg, e);
        if (msg.startsWith("VALIDATION_FAILED")) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", msg, traceId));
        }
        return ResponseEntity.status(500)
                .body(ErrorResponse.of("IMAGE_SEARCH_FAILED", msg, traceId));
    }

    /**
     * 获取数据切片详情（含 SensorStream 列表）
     * <p>
     * GET /v1/data-slices/{sliceId}
     *
     * @param sliceId 切片 UUID
     * @return 200 + 切片详情（含 streams）
     */
    @GetMapping("/{sliceId}")
    public ResponseEntity<?> getDetail(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getDetail request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            SliceDetailResponse response = dataSliceService.getDetail(sliceId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            // 区分异常类型：entity==null → 404，其余 → 500
            if (msg != null && msg.startsWith("SLICE_NOT_FOUND")) {
                log.warn("getDetail: slice not found: sliceId={}", sliceId);
                return ResponseEntity.status(404)
                        .body(ErrorResponse.of("SLICE_NOT_FOUND", msg, traceId));
            }
            log.error("getDetail failed: sliceId={}, error={}", sliceId, msg, e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("INTERNAL_ERROR", msg, traceId));
        } catch (Exception e) {
            log.error("getDetail failed: sliceId={}, error={}", sliceId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("INTERNAL_ERROR", e.getMessage(), traceId));
        }
    }


    /**
     * 删除数据切片
     * <p>
     * DELETE /v1/data-slices/{sliceId}
     * 删除原始 bag 文件、清空关联导出产物、级联删除数据库记录、发送 Kafka 事件。
     *
     * @param sliceId 切片 UUID
     * @param traceId 链路追踪 ID
     * @return 204 No Content
     */
    @RequireSliceDelete
    @DeleteMapping("/{sliceId}")
    public ResponseEntity<?> delete(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("delete request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            dataSliceService.delete(sliceId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("delete failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SLICE_NOT_FOUND", e.getMessage(), traceId));
        }
    }

    /**
     * 批量删除数据切片
     * <p>
     * POST /v1/data-slices/batch-delete
     * application/json
     * <p>
     * 按 sliceIds 逐条删除（删除原始 bag 文件、清空关联导出产物、级联删除数据库记录、发送 Kafka 事件），
     * 返回成功数量与失败明细：成功的切片不在响应中列出，失败的切片附带错误信息。
     *
     * @param request { sliceIds: [...] }（必填，至少一个）
     * @param traceId 链路追踪 ID
     * @return 200 + { success_count, failed: [ { slice_id, error } ] }
     */
    @RequireSliceDelete
    @PostMapping("/batch-delete")
    public ResponseEntity<?> batchDelete(
            @RequestBody ReimportRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        if (request == null || request.getSliceIds() == null || request.getSliceIds().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "sliceIds is required", traceId));
        }
        log.info("batchDelete request: sliceIds={}, traceId={}", request.getSliceIds(), traceId);
        int successCount = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String sliceId : request.getSliceIds()) {
            try {
                dataSliceService.delete(sliceId);
                successCount++;
            } catch (Exception e) {
                log.warn("batchDelete item failed: sliceId={}, error={}", sliceId, e.getMessage());
                Map<String, Object> item = new HashMap<>();
                item.put("slice_id", sliceId);
                item.put("error", e.getMessage() == null ? "INTERNAL_ERROR" : e.getMessage());
                failed.add(item);
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("success_count", successCount);
        body.put("failed", failed);
        log.info("batchDelete result: success={}, failed={}, traceId={}", successCount, failed.size(), traceId);
        return ResponseEntity.ok(body);
    }

    /**
     * 重新导入（重新转换）数据切片
     * <p>
     * POST /v1/data-slices/reimport
     * application/json
     * <p>
     * 逐个 sliceId 执行：① 查询数据库记录 → ② 校验 {@link DataSlice#getImportSourceDir()} 本地目录是否存在，
     * 不存在则由 {@link BosDirectoryRestoreService} 从百度云 BOS 按同名前缀恢复到本地 →
     * ③ 删除数据切片（级联清理文件与记录）→
     * ④ 以实体 {@link DataSlice#getImportSourceDir()} 的父目录（MD5 目录）作为 directory、以 sourceType 作为 source，
     * 调用 {@link LocalDirectoryImportService#importDirectory(DirectoryImportRequest)} 重新触发本地目录导入
     * （异步派发转换流水线）。
     *
     * @param request { sliceIds: [...] }（必填，至少一个）
     * @param traceId 链路追踪 ID
     * @return 202 + 逐项结果列表（sliceId / status: ACCEPTED|FAILED / jobId / message）
     */
    @RequireSliceReimport
    @PostMapping("/reimport")
    public ResponseEntity<?> reimport(
            @RequestBody ReimportRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        if (request == null || request.getSliceIds() == null || request.getSliceIds().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "sliceIds is required", traceId));
        }
        log.info("reimport request: sliceIds={}, traceId={}", request.getSliceIds(), traceId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String sliceId : request.getSliceIds()) {
            Map<String, Object> result = new HashMap<>();
            result.put("sliceId", sliceId);
            try {
                // 1. 查询 sliceId 对应数据库记录
                DataSlice entity = dataSliceMapper.selectById(sliceId);
                if (entity == null) {
                    continue;
                }
//                if (!entity.getStatus().equals("ERROR")) {
//                    continue;
//                }

                // 只允许 MAX_CONCURRENT_TASKS 个并发任务
                long concurrentTasks = localDirectoryImportService.countConcurrentTasks();
                if (concurrentTasks >= MAX_CONCURRENT_TASKS) {
                    result.put("status", "FAILED");
                    result.put("message", "MAX_CONCURRENT_TASKS_EXCEEDED: Only " + MAX_CONCURRENT_TASKS + " concurrent tasks allowed");
                    results.add(result);
                    continue;
                }

                String sourceDir = entity.getImportSourceDir();
                String sourceType = entity.getSourceType();

                // Hirain 源重新拉取：恢复 BOS 数据 → 删除旧切片 → 重新下载/转换/入库
                if ("Hirain".equalsIgnoreCase(sourceType)) {
                    String subDir = entity.getImportBosKey();
                    if (subDir == null || subDir.isBlank()) {
                        result.put("status", "FAILED");
                        result.put("message", "REIMPORT_SOURCE_MISSING: importBosKey is empty, sliceId=" + sliceId);
                        results.add(result);
                        continue;
                    }
                    // 删除数据切片（级联清理文件 + 数据库记录），再重新处理
                    dataSliceService.delete(sliceId);
                    String newSliceId = downloadHirain835Schedule.reprocessSubdir(subDir, entity.getName());
                    result.put("status", "ACCEPTED");
                    result.put("newSliceId", newSliceId);
                    results.add(result);
                    continue;
                }

                // 校验本地源目录是否存在，不存在则从百度云 BOS 按同名前缀恢复到本地（本地路径 = 下载根目录 + 对象key）
                if (sourceDir != null && !sourceDir.isBlank()) {
                    int restored = bosDirectoryRestoreService.restoreIfMissing(sourceDir);
                    if (restored > 0) {
                        log.info("reimport restore from BOS: sliceId={}, sourceDir={}, files={}", sliceId, sourceDir, restored);
                    }
                }
                if (sourceDir == null || sourceDir.isBlank() || sourceType == null || sourceType.isBlank()) {
                    result.put("status", "FAILED");
                    result.put("message", "REIMPORT_SOURCE_MISSING: sliceId=" + sliceId);
                } else {
                    // 2. 删除数据切片（级联清理文件 + 数据库记录）
                    dataSliceService.delete(sliceId);

                    //sourceDir 截取最后一个/前的字符串
                    sourceDir = sourceDir.substring(0, sourceDir.lastIndexOf("/"));

                    // 3. 以 importSourceDir 作为 directory 重新触发本地目录导入
                    DirectoryImportRequest dirReq = new DirectoryImportRequest();
                    dirReq.setDirectory(sourceDir);
                    dirReq.setSource(sourceType);
                    dirReq.setName(entity.getName());
                    DirectoryImportResponse responseDir = localDirectoryImportService.importDirectory(dirReq);
                    result.put("status", "ACCEPTED");
                    result.put("jobId", responseDir.getJobId());
                    result.put("imported", responseDir.getImported() == null ? 0 : responseDir.getImported().size());
                }
            } catch (Exception e) {
                log.warn("reimport failed: sliceId={}, err={}", sliceId, e.getMessage(), e);
                result.put("status", "FAILED");
                result.put("message", e.getMessage() == null ? "UNKNOWN" : e.getMessage());
            }
            results.add(result);
        }
        log.info("reimport done: sliceIds={}, accepted={}, traceId={}",
                request.getSliceIds(),
                results.stream().filter(r -> "ACCEPTED".equals(r.get("status"))).count(),
                traceId);
        return ResponseEntity.status(202).body(results);
    }
}
