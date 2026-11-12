package com.isim.dims.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.SliceStatsResponse;
import com.isim.dims.entity.SliceStats;
import com.isim.dims.mapper.SliceStatsMapper;
import com.isim.dims.security.RequireDashboardWrite;
import com.isim.dims.service.DashboardStatsPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 驾驶舱统计指标 REST API.
 *
 * <p>暴露 slice_stats 表数据供前端驾驶舱页面消费。</p>
 *
 * @feature: dashboard
 */
@Slf4j
@RestController
@RequestMapping("/v1/cockpit-stats")
@RequiredArgsConstructor
public class CockpitStatsController {

    private final SliceStatsMapper sliceStatsMapper;
    /**
     * 统计提取流水线：仅 dims.storage.type=local 模式装配（minio 模式停用，
     * extract_dashboard_stats.py 写本地产物，minio 模式无本地 bagDir）。
     * 可选注入：null（minio 模式）时 trigger 返回 503。
     */
    @Autowired(required = false)
    private DashboardStatsPipelineService dashboardStatsPipelineService;

    /**
     * 获取切片的驾驶舱统计指标.
     *
     * @param sliceId 切片 ID
     * @param traceId 链路追踪 ID
     * @return 统计指标 DTO，无数据时 404
     */
    @GetMapping("/slices/{sliceId}")
    public ResponseEntity<?> getSliceStats(
            @PathVariable String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getSliceStats request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            SliceStats stats = sliceStatsMapper.selectOne(
                    new LambdaQueryWrapper<SliceStats>()
                            .eq(SliceStats::getSliceId, sliceId));
            if (stats == null) {
                return ResponseEntity.status(404)
                        .body(ErrorResponse.of("SLICE_STATS_NOT_FOUND",
                                "No stats found for slice: " + sliceId, traceId));
            }
            return ResponseEntity.ok(SliceStatsResponse.fromEntity(stats));
        } catch (Exception e) {
            log.error("getSliceStats failed: sliceId={}, error={}", sliceId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("COCKPIT_STATS_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 手动触发切片的统计提取（开发/测试用）.
     *
     * @param sliceId 切片 ID
     * @param traceId 链路追踪 ID
     * @return 触发结果
     */
    @RequireDashboardWrite
    @PostMapping("/slices/{sliceId}/trigger")
    public ResponseEntity<?> triggerExtraction(
            @PathVariable String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("triggerExtraction request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            if (dashboardStatsPipelineService == null) {
                // minio 模式：统计提取停用（流水线写本地产物，minio 无本地 bagDir）
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ErrorResponse.of("COCKPIT_TRIGGER_DISABLED",
                                "Dashboard stats extraction is only available in local storage mode", traceId));
            }
            dashboardStatsPipelineService.triggerExtraction(sliceId);
            return ResponseEntity.ok(Map.of("slice_id", sliceId, "status", "TRIGGERED"));
        } catch (Exception e) {
            log.error("triggerExtraction failed: sliceId={}, error={}", sliceId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("COCKPIT_TRIGGER_FAILED", e.getMessage(), traceId));
        }
    }
}
