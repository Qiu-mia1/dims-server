package com.isim.dims.controller;

import com.isim.dims.dto.LogRecordListResponse;
import com.isim.dims.entity.LogRecord;
import com.isim.dims.service.LogRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日志浏览接口
 *
 * <p>日志文件（forever_log / bag_log 等）由 {@code DownloadMMTDataBOSSchedule#downloadSubdirMetaOnly}
 * 在 meta-only 阶段登记到 log_record，本控制器提供列表查询与「整个目录打包下载」。</p>
 *
 * <ul>
 *   <li>GET /v1/log-records                      分页查询日志记录</li>
 *   <li>GET /v1/log-records/{id}                 日志记录详情</li>
 *   <li>GET /v1/log-records/{id}/download        打包下载日志文件所在整个目录（zip）</li>
 * </ul>
 *
 * @feature: ingestion
 */
@Slf4j
@RestController
@RequestMapping("/v1/log-records")
@RequiredArgsConstructor
public class LogRecordController {

    private final LogRecordService logRecordService;

    /**
     * 分页查询日志记录（按日志时间倒序）。
     */
    @GetMapping
    public LogRecordListResponse list(
            @RequestParam(value = "vin", required = false) String vin,
            @RequestParam(value = "log_type", required = false) String logType,
            @RequestParam(value = "event_name", required = false) String eventName,
            @RequestParam(value = "collect_type", required = false) String collectType,
            @RequestParam(value = "project", required = false) String project,
            @RequestParam(value = "car_type", required = false) String carType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        log.info("listLogRecords request: vin={}, logType={}, eventName={}, page={}, pageSize={}",
                vin, logType, eventName, page, pageSize);
        return logRecordService.list(vin, logType, eventName, collectType, project, carType, keyword,
                page, pageSize);
    }

    /**
     * 从 BOS 重新扫描并补登记日志记录（历史数据回填用）。
     *
     * <p>调度按子集目录增量处理，已处理过的历史子集目录不会重新进 meta-only 流程，
     * 需要该接口补建日志索引。</p>
     *
     * @param date 可选，{@code yyyyMMdd} 日期目录；为空时扫描整个 bos 前缀（注意耗时）
     */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(@RequestParam(value = "date", required = false) String date) {
        log.info("rebuildLogRecords request: date={}", date);
        int saved = logRecordService.rebuild(date);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", saved);
        return result;
    }

    /**
     * 日志记录详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<LogRecord> detail(@PathVariable("id") String id) {
        LogRecord record = logRecordService.getById(id);
        if (record == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(record);
    }

    /**
     * 打包下载日志文件所在整个目录。
     *
     * <p>流式转发，不落临时文件；本地已有文件优先读本地，缺失时从 BOS 读取。</p>
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable("id") String id) {
        LogRecord record = logRecordService.getById(id);
        if (record == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String fileName = logRecordService.bundleFileName(record);
        log.info("downloadLogBundle request: id={}, fileName={}, dirPrefix={}",
                id, fileName, record.getDirPrefix());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build());
        StreamingResponseBody body = outputStream -> {
            try {
                logRecordService.writeBundle(id, outputStream);
                outputStream.flush();
            } catch (Exception e) {
                log.error("日志目录打包失败: id={}", id, e);
                throw e;
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
