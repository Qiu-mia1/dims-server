package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.exception.StorageArchivedException;
import com.isim.dims.security.RequireSliceDownload;
import com.isim.dims.security.DownloadTicketService;
import com.isim.dims.service.DataSliceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import jakarta.servlet.http.HttpServletRequest;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 原始切片和 OpenSCENARIO 产物下载接口。 */
@Slf4j
@RestController
@RequestMapping("/v1/data-slices")
@RequiredArgsConstructor
public class SliceDownloadController {

    private final DataSliceService dataSliceService;
    private final DownloadTicketService downloadTicketService;

    /**
     * 服务层异常消息的「错误码前缀」白名单，顺序即匹配优先级（先命中先赢）。
     * 新增错误码时须确认与既有码之间不存在前缀包含关系。
     */
    private static final List<String> DOWNLOAD_ERROR_CODES = List.of(
            "SLICE_NOT_FOUND",
            "SCENE_NOT_READY",
            "SCENE_FILE_NOT_FOUND",
            "BAG_FILE_NOT_FOUND",
            "LOG_PATH_MISSING",
            "LOG_PATH_NOT_ALLOWED",
            "LOG_PATH_NOT_FOUND",
            "LOG_NOT_FOUND",
            "LOG_TOO_LARGE");

    /**
     * Creates a short-lived URL for browser-native download. Permission is checked here.
     *
     * <p>⚠️ 只校验可下载性，**不能 open() 数据流**：本接口只是发一张 5 分钟票据，
     * 不需要读数据内容。若在这里打开 bag 流再关闭，HttpClient 会把未读内容读干
     * （实体流 close() 为了复用连接会读到底）→ 一个 356MB 的 bag 会让取票阻塞 89s（实测），
     * 前端 axios 60s 超时 → 用户侧表现为「点击下载没反应」。
     */
    @RequireSliceDownload
    @GetMapping("/{sliceId}/downloads/rosbag/ticket")
    public ResponseEntity<Map<String, String>> createRosbagDownloadTicket(@PathVariable String sliceId) {
        dataSliceService.assertRosbagDownloadable(sliceId);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("ticket", downloadTicketService.issue(sliceId));
        // 产物格式不止 bag（恒润切片为转换产物打包的 zip），文件名后缀交给服务端推导
        payload.put("file_name", dataSliceService.resolveRosbagFileName(sliceId));
        return ResponseEntity.ok(payload);
    }

    @RequireSliceDownload
    @GetMapping("/{sliceId}/downloads/openscenario")
    public ResponseEntity<StreamingResponseBody> downloadOpenScenario(@PathVariable String sliceId) {
        DataSliceService.OpenScenarioDownload download =
                dataSliceService.prepareOpenScenarioDownload(sliceId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8).build());
        StreamingResponseBody body = output -> {
            try {
                dataSliceService.writeOpenScenarioZip(download, output);
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @RequireSliceDownload
    @GetMapping("/{sliceId}/downloads/carla")
    public ResponseEntity<StreamingResponseBody> downloadCarla(@PathVariable String sliceId) {
        DataSliceService.CarlaDownload download = dataSliceService.prepareCarlaDownload(sliceId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8).build());
        StreamingResponseBody body = output -> {
            try {
                dataSliceService.writeCarlaZip(download, output);
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @RequireSliceDownload
    @GetMapping("/{sliceId}/downloads/rosbag")
    public ResponseEntity<StreamingResponseBody> downloadRosbag(@PathVariable String sliceId) {
        DataSliceService.DownloadContent content = dataSliceService.downloadRosbag(sliceId);
        HttpHeaders headers = new HttpHeaders();
        // 产物已不只有 bag：恒润切片为转换产物打包的 zip，按文件名后缀给准确的 content-type
        headers.setContentType(content.fileName().toLowerCase().endsWith(".zip")
                ? MediaType.parseMediaType("application/zip")
                : MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(content.fileName(), StandardCharsets.UTF_8).build());
        headers.setContentLength(content.size());
        StreamingResponseBody body = output -> {
            try (InputStream input = content.stream()) {
                input.transferTo(output);
                output.flush();
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * Native browser download endpoint. Access is granted only with a signed short-lived ticket.
     *
     * <p>{@code ticket} 用 {@code required=false} 显式兜底：浏览器导航不会带 Authorization，
     * 缺票/坏票都必须是 403（而不是让 {@code @RequestParam} 抛缺参异常被通用 handler 映射成 500）。
     */
    @GetMapping("/{sliceId}/downloads/rosbag/native")
    public ResponseEntity<StreamingResponseBody> downloadRosbagNative(
            @PathVariable String sliceId, @RequestParam(required = false) String ticket) {
        if (ticket == null || ticket.isBlank() || !downloadTicketService.verify(sliceId, ticket)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return downloadRosbag(sliceId);
    }

    /**
     * 日志下载票据：读取 {@code data_slice.import_source_dir} 指向的会话目录，校验
     * 「路径有效 + 有日志文件 + 体积可接受」后发一张短时效票据。
     *
     * <p>为什么把校验放在取票这一步：浏览器原生下载（{@code <a>} 导航）既带不上
     * Authorization 头，也拿不到错误响应体 —— 用户只会看到一个下载失败或空白页。
     * 而「路径不对 / 该目录没有日志 / 日志过大」正是最需要让用户看见的信息，
     * 所以它们必须在返回 JSON 的接口里抛出来，原生接口只负责吐字节。
     *
     * <p>响应除票据外带上<b>本次实际会下载的范围</b>（命中目录 / 文件数 / 原始字节数），
     * 便于前端或脚本在下载前确认「下的到底是不是这个目录、包含哪些文件」。
     */
    @RequireSliceDownload
    @GetMapping("/{sliceId}/downloads/log/ticket")
    public ResponseEntity<Map<String, Object>> createLogDownloadTicket(@PathVariable String sliceId) {
        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload(sliceId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticket", downloadTicketService.issue(sliceId));
        payload.put("file_name", download.fileName());
        payload.put("file_count", download.files().size());
        payload.put("total_size", download.totalSize());
        payload.put("source_dir", download.sourceDir());
        payload.put("files", download.files().stream()
                .map(DataSliceService.LogDownload.LogFile::entryName).toList());
        return ResponseEntity.ok(payload);
    }

    /**
     * 日志下载（带鉴权头直连），供 API/脚本调用。
     *
     * <p>响应体：命中单个日志文件时直出该文件；命中多个时按会话目录结构打包为 ZIP
     * （此时不声明 Content-Length —— ZIP 长度无法预知，声明了会截断响应）。
     */
    @RequireSliceDownload
    @GetMapping("/{sliceId}/downloads/log")
    public ResponseEntity<StreamingResponseBody> downloadLog(@PathVariable String sliceId) {
        return streamLog(dataSliceService.prepareLogDownload(sliceId));
    }

    /** 浏览器原生日志下载：票据口径与 rosbag 一致，缺票/坏票一律 403。 */
    @GetMapping("/{sliceId}/downloads/log/native")
    public ResponseEntity<StreamingResponseBody> downloadLogNative(
            @PathVariable String sliceId, @RequestParam(required = false) String ticket) {
        if (ticket == null || ticket.isBlank() || !downloadTicketService.verify(sliceId, ticket)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return downloadLog(sliceId);
    }

    private ResponseEntity<StreamingResponseBody> streamLog(DataSliceService.LogDownload download) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                download.zip() ? "application/zip" : MediaType.APPLICATION_OCTET_STREAM_VALUE));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8).build());
        if (!download.zip()) {
            headers.setContentLength(download.totalSize());
        }
        StreamingResponseBody body = output -> {
            try {
                dataSliceService.writeLogBundle(download, output);
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @ExceptionHandler(StorageArchivedException.class)
    public ResponseEntity<ErrorResponse> handleArchived(StorageArchivedException e,
            HttpServletRequest request) {
        String traceId = traceId(request);
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleDownloadError(Exception e,
            HttpServletRequest request) {
        if (e instanceof AccessDeniedException ade) {
            // 方法级 @PreAuthorize 的拒绝不能吞成 500：交还 Spring Security 过滤器链，
            // 匿名 → 401（RestAuthenticationEntryPoint），已认证缺权限 → 403（RestAccessDeniedHandler）
            throw ade;
        }
        String traceId = traceId(request);
        log.error("slice download failed: {}", e.getMessage(), e);
        String message = e.getMessage() == null ? "download failed" : e.getMessage();
        String code = resolveDownloadErrorCode(message);
        return ResponseEntity.status(resolveDownloadStatus(code))
                .body(ErrorResponse.of(code, message, traceId));
    }

    /**
     * 服务层用「错误码前缀 + 英文技术描述」抛 RuntimeException，这里把前缀还原成错误码。
     *
     * <p>顺序即优先级：先匹配到的赢。现有码之间没有前缀包含关系
     * （{@code LOG_PATH_NOT_FOUND} 与 {@code LOG_NOT_FOUND} 互为独立码，不是前缀），
     * 新增错误码时务必确认这一点。
     */
    private static String resolveDownloadErrorCode(String message) {
        for (String code : DOWNLOAD_ERROR_CODES) {
            if (message.startsWith(code)) {
                return code;
            }
        }
        return "DOWNLOAD_FAILED";
    }

    private static HttpStatus resolveDownloadStatus(String code) {
        return switch (code) {
            // 「找不到」：切片没有、场景文件没有、bag 没有，以及日志三类缺失
            case "SLICE_NOT_FOUND", "SCENE_FILE_NOT_FOUND", "BAG_FILE_NOT_FOUND",
                 "LOG_PATH_MISSING", "LOG_PATH_NOT_FOUND", "LOG_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            // 场景还没跑完：等一会儿可以重试，语义上是冲突而非错误
            case "SCENE_NOT_READY" -> HttpStatus.CONFLICT;
            // 日志目录不在允许的根目录内：属越权读取，返回 403 而不是 404（不泄露路径是否存在）
            case "LOG_PATH_NOT_ALLOWED" -> HttpStatus.FORBIDDEN;
            // 日志体积超上限：请求本身合法，但体量不可接受
            case "LOG_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String traceId(HttpServletRequest request) {
        String value = request.getHeader("X-Trace-Id");
        return value == null || value.isBlank() ? "auto-generated" : value;
    }
}
