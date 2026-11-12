package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.exception.StorageArchivedException;
import com.isim.dims.exception.StorageException;
import com.isim.dims.exception.StorageNotFoundException;
import com.isim.dims.service.StorageStat;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 视频文件异步流式服务 Controller
 *
 * <p>提供高效的视频文件 HTTP 服务，支持 Range 请求（206 Partial Content），
 * 使用 StreamingResponseBody 在异步线程中传输文件，避免长时间占用 Tomcat 工作线程池。</p>
 *
 * <p>MinIO 迁移后：文件读取经 {@link StorageAccess} 门面（key 由 sliceId+filename
 * 服务端构造），Range 请求在 MINIO 实现中为服务端裁剪；归档记录（storage_type=LOCAL
 * 且 minio 模式）返回 410 DATA_ARCHIVED。</p>
 *
 * <h3>解决的问题</h3>
 * <ul>
 *   <li><b>同步 IO 阻塞 Tomcat 线程</b>：Spring MVC 默认的 ResourceHttpRequestHandler
 *       使用同步 IO，视频传输期间完全占用 Tomcat 工作线程。12 路视频并发时，
 *       大量线程被 IO 阻塞，影响其他 API 响应。</li>
 *   <li><b>Range 请求优化</b>：视频播放器的 seek 操作通过 HTTP Range 头实现。
 *       本控制器正确处理 206 Partial Content，支持随机访问。</li>
 *   <li><b>缓存优化</b>：设置 private max-age=3600 缓存策略，减少浏览器重复请求。</li>
 * </ul>
 *
 * <p>优先于 WebMvcConfig 中的 ResourceHandler 匹配（@RequestMapping 优先级更高）。</p>
 *
 * <h3>请求示例</h3>
 * <pre>
 *   GET /data/videos/{sliceId}/{filename}.mp4
 *   Range: bytes=0-1023
 *   → 206 Partial Content
 *   Content-Range: bytes 0-1023/{fileLength}
 * </pre>
 * @feature: visualization
 */
@Slf4j
@RestController
@RequestMapping("/data/videos")
@RequiredArgsConstructor
public class VideoStreamingController {

    /**
     * sliceId 白名单：标准 UUID 格式（严格正则，UUID.fromString 对 "1-1-1-1-1" 等宽松格式也会放行，
     * 这里收紧到完整 8-4-4-4-12 十六进制分组）。
     */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * filename 白名单：仅允许字母/数字/点/下划线/短横线。
     * 显式排除 "/"（路径分隔符）与 ".."（父目录），杜绝 CVE-2018-1271 同类的
     * 编码穿越（如 {@code ..%2f}）进入文件系统。
     */
    private static final Pattern FILENAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private final StorageAccess storageAccess;

    /**
     * 流式视频文件（支持 HTTP Range 断点续传）
     *
     * @param sliceId     切片 UUID
     * @param filename    视频文件名（含扩展名）
     * @param rangeHeader HTTP Range 请求头（可选），格式："bytes=start-end"
     * @return 206 Partial Content（有 Range）或 200 OK（全量）
     */
    @GetMapping("/{sliceId}/{filename:.+}")
    public ResponseEntity<StreamingResponseBody> streamVideo(
            @PathVariable String sliceId,
            @PathVariable String filename,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader) {

        // 0. 目录穿越防线 1/3（匿名可访问路径，纵深防御必须前置）：
        //    sliceId 必须为 UUID、filename 必须命中白名单（排除 / 与 .. ），
        //    任一不符 → 404（不泄露是否存在目标文件）。
        if (sliceId == null || filename == null
                || !UUID_PATTERN.matcher(sliceId).matches()
                || !FILENAME_PATTERN.matcher(filename).matches()
                || "..".equals(filename)) {
            log.warn("Video request rejected (invalid path params): sliceId={}, filename={}",
                    sliceId, filename);
            return ResponseEntity.notFound().build();
        }

        // 1. 构造存储 key（服务端白名单构造，sliceId+filename 双白名单已校验）
        String key = StorageKeys.video(sliceId, filename);

        StorageStat stat;
        try {
            // 2. 归档拦截（minio 模式 + storage_type=LOCAL → 410）+ 对象元信息
            String readKey = storageAccess.resolveReadKey(sliceId, key);
            stat = storageAccess.stat(readKey);
        } catch (StorageArchivedException e) {
            // 归档切片（storage_type=LOCAL + minio 模式）：数据内容读取 → 410
            // （return 类型为 ResponseEntity<StreamingResponseBody>，错误响应经 @ExceptionHandler 映射）
            throw e;
        } catch (StorageNotFoundException e) {
            log.warn("Video file not found: sliceId={}, filename={}", sliceId, filename);
            return ResponseEntity.notFound().build();
        } catch (StorageException e) {
            log.warn("Video request failed: sliceId={}, filename={}, error={}", sliceId, filename, e.getMessage());
            return ResponseEntity.notFound().build();
        }

        long fileLength = stat.size();
        String contentType = stat.contentType() != null
                ? stat.contentType()
                : resolveContentType(filename);

        CacheControl cacheControl = CacheControl.maxAge(3600, TimeUnit.SECONDS)
                .cachePrivate()
                .mustRevalidate();

        // 3. 处理 Range 请求（视频播放器 seek 的关键）
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            return handleRangeRequest(key, fileLength, contentType, rangeHeader, cacheControl);
        }

        // 4. 全量响应（首次加载）
        log.debug("Streaming full video: sliceId={}, filename={}, size={}",
                sliceId, filename, fileLength);

        StreamingResponseBody body = outputStream -> {
            try (InputStream is = storageAccess.open(key)) {
                is.transferTo(outputStream);
                outputStream.flush();
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(fileLength)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .cacheControl(cacheControl)
                .body(body);
    }

    /**
     * 归档切片拦截：storage_type=LOCAL + minio 模式读取数据内容 → 410 DATA_ARCHIVED
     *
     * <p>契约裁决 2：归档数据内容读取返回 HTTP 410 + ErrorResponse.of 结构。
     * streamVideo 返回类型为 ResponseEntity&lt;StreamingResponseBody&gt;（泛型具体化是
     * StreamingResponseBodyReturnValueHandler 匹配的前提，ResponseEntity&lt;?&gt; 会导致
     * "No converter for ..." 500），错误响应只能经 @ExceptionHandler 映射。</p>
     */
    @ExceptionHandler(StorageArchivedException.class)
    public ResponseEntity<ErrorResponse> handleArchived(StorageArchivedException e) {
        log.warn("Video request rejected (archived slice): message={}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), "auto-generated"));
    }

    /**
     * 处理 HTTP Range 请求（206 Partial Content）
     *
     * <p>视频播放器通过 Range 头实现 seek 和分块加载。
     * 注意：只处理单 Range（"bytes=start-end"），忽略多 Range（逗号分隔），
     * 因为大多数浏览器/播放器只发送单 Range。</p>
     */
    private ResponseEntity<StreamingResponseBody> handleRangeRequest(
            String key,
            long fileLength,
            String contentType,
            String rangeHeader,
            CacheControl cacheControl) {

        long[] parsed = parseRange(rangeHeader, fileLength);

        if (parsed == null) {
            // Range 语法错误或超出范围 → 416
            log.warn("Invalid range request: {}, fileLength={}", rangeHeader, fileLength);
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileLength)
                    .build();
        }

        long rangeStart = parsed[0];
        long rangeEnd = parsed[1];
        long contentLength = rangeEnd - rangeStart + 1;

        log.debug("Range request: {}-{}/{} (contentLength={})",
                rangeStart, rangeEnd, fileLength, contentLength);

        StreamingResponseBody body = outputStream -> {
            try (InputStream is = storageAccess.open(key, rangeStart, rangeEnd)) {
                is.transferTo(outputStream);
                outputStream.flush();
            }
        };

        String contentRange = "bytes " + rangeStart + "-" + rangeEnd + "/" + fileLength;

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(contentLength)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, contentRange)
                .cacheControl(cacheControl)
                .body(body);
    }

    /**
     * 解析 HTTP Range 头的 "bytes=start-end" 格式
     *
     * @return [start, end] 数组，解析失败或不满足时返回 null
     */
    private long[] parseRange(String rangeHeader, long fileLength) {
        try {
            // 去掉 "bytes=" 前缀
            String rangeValue = rangeHeader.substring(6).trim();

            // 只处理单 Range（逗号分隔的多 Range 回退为全量响应）
            if (rangeValue.contains(",")) {
                return null;
            }

            long start, end;

            if (rangeValue.startsWith("-")) {
                // 后缀范围: "bytes=-500" → 最后 500 字节
                long suffixLength = Long.parseLong(rangeValue.substring(1));
                if (suffixLength <= 0) return null;
                start = Math.max(0, fileLength - suffixLength);
                end = fileLength - 1;
            } else if (rangeValue.endsWith("-")) {
                // 开放范围: "bytes=500-" → 从 500 到文件尾
                start = Long.parseLong(rangeValue.substring(0, rangeValue.length() - 1));
                end = fileLength - 1;
            } else {
                // 闭合范围: "bytes=0-1023"
                int dashIndex = rangeValue.indexOf('-');
                if (dashIndex <= 0) return null;
                start = Long.parseLong(rangeValue.substring(0, dashIndex));
                end = Long.parseLong(rangeValue.substring(dashIndex + 1));
            }

            // 验证范围有效性
            if (start < 0 || end < start || start >= fileLength) {
                return null;
            }
            if (end >= fileLength) {
                end = fileLength - 1;
            }

            return new long[]{start, end};

        } catch (Exception e) {
            log.warn("Failed to parse Range header: {}", rangeHeader, e);
            return null;
        }
    }

    /**
     * 根据文件扩展名推断 Content-Type
     */
    private String resolveContentType(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) {
            return "application/octet-stream";
        }
        String ext = filename.substring(dotIndex + 1).toLowerCase();
        switch (ext) {
            case "mp4":     return "video/mp4";
            case "webm":    return "video/webm";
            case "avi":     return "video/x-msvideo";
            case "mov":     return "video/quicktime";
            case "mkv":     return "video/x-matroska";
            case "ts":      return "video/mp2t";
            case "m3u8":    return "application/x-mpegURL";
            default:        return "application/octet-stream";
        }
    }
}
