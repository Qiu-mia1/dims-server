package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.PriorityUpdateRequest;
import com.isim.dims.dto.SceneRuleConfigUpdateRequest;
import com.isim.dims.dto.SceneRuleCreateRequest;
import com.isim.dims.dto.SceneRuleDTO;
import com.isim.dims.dto.SceneRuleUpdateRequest;
import com.isim.dims.entity.SceneRuleConfig;
import com.isim.dims.exception.SceneRuleConfigNotFoundException;
import com.isim.dims.exception.SceneRuleNotFoundException;
import com.isim.dims.security.RequireRuleWrite;
import com.isim.dims.service.SceneRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 场景规则 Controller
 *
 * 负责场景判断规则与阈值配置的 REST API：
 *   GET    /v1/scene-rules                规则列表（?enabled=true 过滤）
 *   GET    /v1/scene-rules/{id}           单条规则
 *   POST   /v1/scene-rules                新增规则
 *   PUT    /v1/scene-rules/{id}           更新规则
 *   PATCH  /v1/scene-rules/{id}/toggle    启用/禁用切换
 *   PUT    /v1/scene-rules/priority       批量更新优先级
 *   GET    /v1/scene-rules/export         导出规则配置（json|yaml，v2 格式，供 Python 消费）
 *   GET    /v1/scene-rules/config         全局阈值配置列表
 *   PUT    /v1/scene-rules/config/{key}   更新阈值配置
 *
 * v2 API 变更（对齐 dims-python/scene_extraction 配置驱动引擎）：
 *   规则结构从 v1 的 conditions + time_window + output_params 升级为
 *   logic / confidence / entity_roles / output_parameters / postprocess /
 *   features / min_engine_version；v1 的 output_params 保留（@Deprecated）
 *   兼容接收，新客户端请使用 output_parameters。
 *
 * 错误码：SCENE_RULE_NOT_FOUND(404) / SCENE_RULE_CONFIG_NOT_FOUND(404)
 *        SCENE_RULE_CONFLICT(409，scene_type 唯一冲突) / INVALID_PARAMETER(400)
 * @feature: ingestion
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class SceneRuleController {

    private final SceneRuleService sceneRuleService;

    /**
     * 获取所有规则，支持按启用状态过滤
     *
     * GET /v1/scene-rules?enabled=true
     *
     * @param enabled 是否启用（可选，true/false）
     * @param traceId 链路追踪 ID
     * @return 200 + 规则列表
     */
    @GetMapping("/scene-rules")
    public ResponseEntity<?> listRules(
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("listSceneRules request: enabled={}, traceId={}", enabled, traceId);
        try {
            List<SceneRuleDTO> rules = sceneRuleService.list(enabled);
            return ResponseEntity.ok(rules);
        } catch (Exception e) {
            log.error("listSceneRules failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SCENE_RULE_QUERY_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 获取单条规则
     *
     * GET /v1/scene-rules/{id}
     *
     * @param id      规则 UUID
     * @param traceId 链路追踪 ID
     * @return 200 + 规则详情
     */
    @GetMapping("/scene-rules/{id}")
    public ResponseEntity<?> getRule(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getSceneRule request: id={}, traceId={}", id, traceId);
        try {
            return ResponseEntity.ok(sceneRuleService.get(id));
        } catch (SceneRuleNotFoundException e) {
            log.warn("getSceneRule not found: {}", e.getMessage());
            return ResponseEntity.status(404)
                    .body(ErrorResponse.of("SCENE_RULE_NOT_FOUND", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("getSceneRule failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SCENE_RULE_QUERY_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 新增规则
     *
     * POST /v1/scene-rules
     *
     * @param request 创建请求（scene_type / conditions 必填；time_window /
     *                output_parameters 建议必填，v1 遗留 output_params 兼容接收）
     * @param traceId 链路追踪 ID
     * @return 201 + 创建的规则详情
     */
    @RequireRuleWrite
    @PostMapping("/scene-rules")
    public ResponseEntity<?> createRule(
            @RequestBody SceneRuleCreateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("createSceneRule request: sceneType={}, traceId={}",
                request != null ? request.getSceneType() : null, traceId);
        try {
            SceneRuleDTO rule = sceneRuleService.create(request);
            return ResponseEntity.status(201).body(rule);
        } catch (IllegalArgumentException e) {
            log.warn("createSceneRule invalid parameter: {}", e.getMessage());
            return ResponseEntity.status(400)
                    .body(ErrorResponse.of("INVALID_PARAMETER", e.getMessage(), traceId));
        } catch (DuplicateKeyException e) {
            log.warn("createSceneRule conflict (scene_type duplicate): {}", e.getMessage());
            return ResponseEntity.status(409)
                    .body(ErrorResponse.of("SCENE_RULE_CONFLICT", "scene_type already exists", traceId));
        } catch (Exception e) {
            log.error("createSceneRule failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SCENE_RULE_CREATE_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 更新规则（仅更新非空字段）
     *
     * PUT /v1/scene-rules/{id}
     *
     * @param id      规则 UUID
     * @param request 更新请求
     * @param traceId 链路追踪 ID
     * @return 200 + 更新后的规则详情
     */
    @RequireRuleWrite
    @PutMapping("/scene-rules/{id}")
    public ResponseEntity<?> updateRule(
            @PathVariable("id") String id,
            @RequestBody SceneRuleUpdateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("updateSceneRule request: id={}, traceId={}", id, traceId);
        try {
            return ResponseEntity.ok(sceneRuleService.update(id, request));
        } catch (SceneRuleNotFoundException e) {
            log.warn("updateSceneRule not found: {}", e.getMessage());
            return ResponseEntity.status(404)
                    .body(ErrorResponse.of("SCENE_RULE_NOT_FOUND", e.getMessage(), traceId));
        } catch (DuplicateKeyException e) {
            log.warn("updateSceneRule conflict (scene_type duplicate): {}", e.getMessage());
            return ResponseEntity.status(409)
                    .body(ErrorResponse.of("SCENE_RULE_CONFLICT", "scene_type already exists", traceId));
        } catch (Exception e) {
            log.error("updateSceneRule failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SCENE_RULE_UPDATE_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 启用/禁用切换
     *
     * PATCH /v1/scene-rules/{id}/toggle
     *
     * @param id      规则 UUID
     * @param traceId 链路追踪 ID
     * @return 200 + 切换后的规则详情
     */
    @RequireRuleWrite
    @PatchMapping("/scene-rules/{id}/toggle")
    public ResponseEntity<?> toggleRule(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("toggleSceneRule request: id={}, traceId={}", id, traceId);
        try {
            return ResponseEntity.ok(sceneRuleService.toggle(id));
        } catch (SceneRuleNotFoundException e) {
            log.warn("toggleSceneRule not found: {}", e.getMessage());
            return ResponseEntity.status(404)
                    .body(ErrorResponse.of("SCENE_RULE_NOT_FOUND", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("toggleSceneRule failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SCENE_RULE_UPDATE_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 批量更新优先级
     *
     * PUT /v1/scene-rules/priority
     * 请求体：[{ "id": "<uuid>", "priority": 1 }, ...]
     *
     * @param updates 优先级更新列表
     * @param traceId 链路追踪 ID
     * @return 200 + { code, message }
     */
    @RequireRuleWrite
    @PutMapping("/scene-rules/priority")
    public ResponseEntity<?> updatePriority(
            @RequestBody List<PriorityUpdateRequest> updates,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("updateSceneRulePriority request: count={}, traceId={}",
                updates != null ? updates.size() : null, traceId);
        try {
            sceneRuleService.updatePriority(updates);
            return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
        } catch (IllegalArgumentException e) {
            log.warn("updateSceneRulePriority invalid parameter: {}", e.getMessage());
            return ResponseEntity.status(400)
                    .body(ErrorResponse.of("INVALID_PARAMETER", e.getMessage(), traceId));
        } catch (SceneRuleNotFoundException e) {
            log.warn("updateSceneRulePriority not found: {}", e.getMessage());
            return ResponseEntity.status(404)
                    .body(ErrorResponse.of("SCENE_RULE_NOT_FOUND", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("updateSceneRulePriority failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SCENE_RULE_UPDATE_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 导出规则配置（供 Python 规则引擎消费）
     *
     * GET /v1/scene-rules/export?format=json
     * 仅包含启用中的规则；format=yaml 输出 YAML 文本。
     *
     * v2 导出结构（对齐 dims-python/scene_extraction 配置驱动引擎
     * engine/model.py + rules/*.yaml）：
     * <pre>
     * version: 2
     * min_engine_version: "2.0"
     * features: [...]                    # 可选：声明式复合特征 {name, expression, unit}
     * rules:
     *   - scene_type: "..."
     *     description: "..."
     *     confidence: 0.85
     *     priority: 2
     *     logic: "all"                    # all | any
     *     entity_roles: {...}             # key 为角色名，value 为 {selector, types}
     *     conditions: [...]               # 元素：{field, operator, value?, value_type,
     *                                     #        unit?, negate?, params?, description?}
     *     time_window: {...}              # {min_duration_s, max_duration_s}
     *     output_parameters: [...]        # 复数；元素 {name, feature, aggregate, round}
     *     postprocess: [...]              # {type, params}
     * </pre>
     * 兼容性说明：v1 的 output_params（字符串数组）已升级为 v2 的
     * output_parameters（对象数组，含 name / feature / aggregate / round），
     * 消费方须按 v2 结构解析导出文件。
     *
     * @param format  json（默认）| yaml
     * @param traceId 链路追踪 ID
     * @return 200 + 配置内容（json → application/json，yaml → application/x-yaml）
     */
    @GetMapping("/scene-rules/export")
    public ResponseEntity<?> exportRules(
            @RequestParam(value = "format", required = false, defaultValue = "json") String format,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("exportSceneRules request: format={}, traceId={}", format, traceId);
        try {
            String content = sceneRuleService.exportRules(format);
            MediaType contentType = "yaml".equalsIgnoreCase(format)
                    ? MediaType.parseMediaType("application/x-yaml; charset=UTF-8")
                    : MediaType.APPLICATION_JSON;
            return ResponseEntity.ok().contentType(contentType).body(content);
        } catch (IllegalArgumentException e) {
            log.warn("exportSceneRules invalid parameter: {}", e.getMessage());
            return ResponseEntity.status(400)
                    .body(ErrorResponse.of("INVALID_PARAMETER", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("exportSceneRules failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SCENE_RULE_EXPORT_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 获取全局阈值配置
     *
     * GET /v1/scene-rules/config
     *
     * @param traceId 链路追踪 ID
     * @return 200 + 阈值配置列表
     */
    @GetMapping("/scene-rules/config")
    public ResponseEntity<?> listConfigs(
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("listSceneRuleConfigs request: traceId={}", traceId);
        try {
            List<SceneRuleConfig> configs = sceneRuleService.getConfigs();
            return ResponseEntity.ok(configs);
        } catch (Exception e) {
            log.error("listSceneRuleConfigs failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SCENE_RULE_CONFIG_QUERY_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 更新阈值配置
     *
     * PUT /v1/scene-rules/config/{key}
     *
     * @param key     阈值配置 key（如 lane_width_typical）
     * @param request 更新请求（config_value 必填）
     * @param traceId 链路追踪 ID
     * @return 200 + 更新后的阈值配置
     */
    @RequireRuleWrite
    @PutMapping("/scene-rules/config/{key}")
    public ResponseEntity<?> updateConfig(
            @PathVariable("key") String key,
            @RequestBody SceneRuleConfigUpdateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("updateSceneRuleConfig request: key={}, traceId={}", key, traceId);
        try {
            return ResponseEntity.ok(sceneRuleService.updateConfig(key, request));
        } catch (SceneRuleConfigNotFoundException e) {
            log.warn("updateSceneRuleConfig not found: {}", e.getMessage());
            return ResponseEntity.status(404)
                    .body(ErrorResponse.of("SCENE_RULE_CONFIG_NOT_FOUND", e.getMessage(), traceId));
        } catch (IllegalArgumentException e) {
            log.warn("updateSceneRuleConfig invalid parameter: {}", e.getMessage());
            return ResponseEntity.status(400)
                    .body(ErrorResponse.of("INVALID_PARAMETER", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("updateSceneRuleConfig failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SCENE_RULE_CONFIG_UPDATE_FAILED", e.getMessage(), traceId));
        }
    }
}
