package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.SensorCalibrationRequest;
import com.isim.dims.dto.SensorCalibrationResponse;
import com.isim.dims.dto.SensorCalibrationSet;
import com.isim.dims.security.RequireCalibrationWrite;
import com.isim.dims.service.CalibrationService;
import com.isim.dims.util.TraceIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 传感器标定 Controller
 *
 * 对应 OpenAPI v1.3.0 paths:
 *   GET  /v1/data-slices/{sliceId}/calibrations
 *   POST /v1/data-slices/{sliceId}/calibrations
 * @feature: calibration
 */
@Slf4j
@RestController
@RequestMapping("/v1/data-slices/{sliceId}/calibrations")
@RequiredArgsConstructor
public class CalibrationController {

    private final CalibrationService calibrationService;

    /**
     * 查询传感器标定
     *
     * GET /v1/data-slices/{sliceId}/calibrations
     *
     * 无标定 → 200 + has_calibration=false（不报错），前端回退默认布局并标注「标定缺失」。
     *
     * @return 200 + SensorCalibrationSet
     */
    @GetMapping
    public ResponseEntity<?> getCalibrations(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getSensorCalibrations request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            SensorCalibrationSet response = calibrationService.getCalibrationSet(sliceId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getSensorCalibrations failed: {}", e.getMessage(), e);
            return toError(e, "CALIBRATIONS_FAILED", traceId);
        }
    }

    /**
     * 摄入传感器标定（按 slice_id+sensor_name upsert）
     *
     * POST /v1/data-slices/{sliceId}/calibrations
     * body: { sensor_name, sensor_type, x, y, z, yaw, pitch, roll, frame?, unit?, source? }
     *
     * x/y/z unit: m；yaw/pitch/roll unit: rad；frame/unit 默认 ego/m。
     * 参数非法 → 400 CALIBRATION_INVALID。
     *
     * @return 201 + SensorCalibrationResponse
     */
    @RequireCalibrationWrite
    @PostMapping
    public ResponseEntity<?> createCalibration(
            @PathVariable("sliceId") String sliceId,
            @RequestBody SensorCalibrationRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("createSensorCalibration request: sliceId={}, sensorName={}, sensorType={}, traceId={}",
                sliceId,
                request != null ? request.getSensorName() : null,
                request != null ? request.getSensorType() : null,
                traceId);
        try {
            SensorCalibrationResponse response = calibrationService.createCalibration(sliceId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("createSensorCalibration failed: {}", e.getMessage(), e);
            return toError(e, "CALIBRATION_CREATE_FAILED", traceId);
        }
    }

    private ResponseEntity<ErrorResponse> toError(Exception e, String fallbackCode, String traceId) {
        String msg = e.getMessage();
        if (msg != null) {
            if (msg.startsWith("SLICE_NOT_FOUND:")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("SLICE_NOT_FOUND", msg, traceId));
            }
            if (msg.startsWith("CALIBRATION_INVALID:")) {
                return ResponseEntity.badRequest()
                        .body(ErrorResponse.of("CALIBRATION_INVALID", msg, traceId));
            }
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(fallbackCode, msg != null ? msg : e.toString(), traceId));
    }
}
