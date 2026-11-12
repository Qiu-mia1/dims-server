package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.PriorityUpdateRequest;
import com.isim.dims.dto.SceneRuleConfigUpdateRequest;
import com.isim.dims.dto.SceneRuleCreateRequest;
import com.isim.dims.dto.SceneRuleDTO;
import com.isim.dims.dto.SceneRuleUpdateRequest;
import com.isim.dims.entity.SceneRule;
import com.isim.dims.entity.SceneRuleConfig;
import com.isim.dims.exception.SceneRuleConfigNotFoundException;
import com.isim.dims.exception.SceneRuleNotFoundException;
import com.isim.dims.mapper.SceneRuleConfigMapper;
import com.isim.dims.mapper.SceneRuleMapper;
import com.isim.dims.service.SceneRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景规则 Service 实现
 *
 * 核心业务逻辑：
 * 1. 规则 CRUD：列表（启用过滤）、单条、创建、部分更新、启用/禁用切换
 * 2. 优先级批量调整（事务）
 * 3. 规则导出（v2 格式）：与 dims-python/scene_extraction/rules/*.yaml 结构等价，
 *    供 Python 配置驱动引擎（engine/rules RuleLoader）消费
 * 4. 全局阈值配置管理
 *
 * 导出格式说明（v2，对齐 dims-python/scene_extraction 配置驱动引擎）：
 * - JSON 用 Spring 注入的 ObjectMapper；YAML 用 SnakeYAML（spring-boot-starter
 *   传递依赖，无需新增依赖）。导出 Map 全部使用 LinkedHashMap 保证键序稳定。
 * - 顶层结构：version: 2（Integer，RuleLoader 校验 version == 2）/
 *   min_engine_version（启用规则中的最高版本门禁，字符串）/ features
 *   （合并去重，可选）/ rules（仅启用规则，按 priority 升序、scene_type 升序）。
 * - 每条规则：scene_type / description / confidence / priority / logic /
 *   entity_roles / conditions / time_window / output_parameters / postprocess。
 *   conditions 元素保留 value_type / params / negate / unit，null/空值省略
 *   （引擎侧有缺省语义：value_type=literal、params={}、negate=false）。
 * - v1 的 output_params（字符串数组）已升级为 v2 的 output_parameters
 *   （对象数组，含 name / feature / aggregate / round）。
 * @feature: ingestion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneRuleServiceImpl implements SceneRuleService {

    private final SceneRuleMapper sceneRuleMapper;
    private final SceneRuleConfigMapper sceneRuleConfigMapper;
    private final ObjectMapper objectMapper;

    /** 导出支持的格式 */
    private static final String FORMAT_JSON = "json";
    private static final String FORMAT_YAML = "yaml";

    // ========================================================================
    // 规则列表 / 单条查询
    // ========================================================================

    @Override
    public List<SceneRuleDTO> list(Boolean enabled) {
        LambdaQueryWrapper<SceneRule> wrapper = new LambdaQueryWrapper<>();
        if (enabled != null) {
            wrapper.eq(SceneRule::getIsEnabled, enabled);
        }
        wrapper.orderByAsc(SceneRule::getPriority, SceneRule::getSceneType);
        List<SceneRule> rules = sceneRuleMapper.selectList(wrapper);
        List<SceneRuleDTO> dtos = new ArrayList<>(rules.size());
        for (SceneRule rule : rules) {
            dtos.add(toDto(rule));
        }
        return dtos;
    }

    @Override
    public SceneRuleDTO get(String id) {
        return toDto(getRule(id));
    }

    // ========================================================================
    // 创建规则
    // ========================================================================

    @Override
    @Transactional
    public SceneRuleDTO create(SceneRuleCreateRequest request) {
        if (request.getSceneType() == null || request.getSceneType().isBlank()) {
            throw new IllegalArgumentException("INVALID_PARAMETER: scene_type is required");
        }
        if (request.getConditions() == null || request.getConditions().isEmpty()) {
            throw new IllegalArgumentException("INVALID_PARAMETER: conditions is required and must not be empty");
        }
        if (request.getTimeWindow() == null) {
            throw new IllegalArgumentException("INVALID_PARAMETER: time_window is required");
        }
        // v2 以 output_parameters 为准；v1 遗留 output_params 兼容接收
        if (request.getOutputParameters() == null && request.getOutputParams() == null) {
            throw new IllegalArgumentException("INVALID_PARAMETER: output_parameters is required");
        }
        if (request.getSceneType().length() > 32) {
            throw new IllegalArgumentException("INVALID_PARAMETER: scene_type must be <= 32 chars");
        }
        if (request.getDescription() != null && request.getDescription().length() > 255) {
            throw new IllegalArgumentException("INVALID_PARAMETER: description must be <= 255 chars");
        }
        validateLogic(request.getLogic());
        if (request.getConfidence() != null && (request.getConfidence() <= 0 || request.getConfidence() > 1)) {
            throw new IllegalArgumentException("INVALID_PARAMETER: confidence must be > 0 and <= 1");
        }

        SceneRule rule = new SceneRule();
        rule.setSceneType(request.getSceneType());
        rule.setDescription(request.getDescription());
        rule.setReference(request.getReference());
        rule.setPriority(request.getPriority());
        rule.setIsEnabled(request.getIsEnabled() != null ? request.getIsEnabled() : true);
        rule.setVersion(request.getVersion() != null ? request.getVersion() : "1.0");
        rule.setConditions(request.getConditions());
        rule.setTimeWindow(request.getTimeWindow());
        rule.setLogic(request.getLogic() != null ? request.getLogic() : "all");
        rule.setConfidence(request.getConfidence() != null ? request.getConfidence() : 0.85);
        rule.setMinEngineVersion(request.getMinEngineVersion() != null ? request.getMinEngineVersion() : "2.0");
        rule.setEntityRoles(toEntityRolesMap(request.getEntityRoles()));
        rule.setOutputParameters(toOutputParamsList(request.getOutputParameters()));
        rule.setPostprocess(toPostprocessList(request.getPostprocess()));
        rule.setFeatures(toFeaturesList(request.getFeatures()));
        rule.setOutputParams(request.getOutputParams());
        // v2-only 请求不传旧字段 output_params 时，兜底空列表避免 DB NOT NULL 约束
        // （V11 已将 output_params 改为 NULL，此处为双保险）
        if (rule.getOutputParams() == null) {
            rule.setOutputParams(Collections.emptyList());
        }
        sceneRuleMapper.insert(rule);

        log.info("SceneRule created: id={}, sceneType={}, priority={}", rule.getId(), rule.getSceneType(), rule.getPriority());
        return toDto(rule);
    }

    // ========================================================================
    // 更新规则（仅更新非空字段）
    // ========================================================================

    @Override
    @Transactional
    public SceneRuleDTO update(String id, SceneRuleUpdateRequest request) {
        SceneRule existing = getRule(id);
        validateLogic(request.getLogic());

        if (request.getSceneType() != null) {
            if (request.getSceneType().length() > 32) {
                throw new IllegalArgumentException("INVALID_PARAMETER: scene_type must be <= 32 chars");
            }
            existing.setSceneType(request.getSceneType());
        }
        if (request.getDescription() != null) {
            if (request.getDescription().length() > 255) {
                throw new IllegalArgumentException("INVALID_PARAMETER: description must be <= 255 chars");
            }
            existing.setDescription(request.getDescription());
        }
        if (request.getReference() != null) {
            existing.setReference(request.getReference());
        }
        if (request.getPriority() != null) {
            existing.setPriority(request.getPriority());
        }
        if (request.getIsEnabled() != null) {
            existing.setIsEnabled(request.getIsEnabled());
        }
        if (request.getVersion() != null) {
            existing.setVersion(request.getVersion());
        }
        if (request.getConditions() != null) {
            existing.setConditions(request.getConditions());
        }
        if (request.getTimeWindow() != null) {
            existing.setTimeWindow(request.getTimeWindow());
        }
        if (request.getLogic() != null) {
            existing.setLogic(request.getLogic());
        }
        if (request.getConfidence() != null) {
            if (request.getConfidence() <= 0 || request.getConfidence() > 1) {
                throw new IllegalArgumentException("INVALID_PARAMETER: confidence must be > 0 and <= 1");
            }
            existing.setConfidence(request.getConfidence());
        }
        if (request.getMinEngineVersion() != null) {
            existing.setMinEngineVersion(request.getMinEngineVersion());
        }
        if (request.getEntityRoles() != null) {
            existing.setEntityRoles(toEntityRolesMap(request.getEntityRoles()));
        }
        if (request.getOutputParameters() != null) {
            existing.setOutputParameters(toOutputParamsList(request.getOutputParameters()));
        }
        if (request.getPostprocess() != null) {
            existing.setPostprocess(toPostprocessList(request.getPostprocess()));
        }
        if (request.getFeatures() != null) {
            existing.setFeatures(toFeaturesList(request.getFeatures()));
        }
        if (request.getOutputParams() != null) {
            existing.setOutputParams(request.getOutputParams());
        }

        sceneRuleMapper.updateById(existing);
        log.info("SceneRule updated: id={}, sceneType={}", id, existing.getSceneType());
        return toDto(existing);
    }

    // ========================================================================
    // 启用/禁用切换
    // ========================================================================

    @Override
    @Transactional
    public SceneRuleDTO toggle(String id) {
        SceneRule existing = getRule(id);
        existing.setIsEnabled(Boolean.TRUE.equals(existing.getIsEnabled()) ? false : true);
        sceneRuleMapper.updateById(existing);
        log.info("SceneRule toggled: id={}, isEnabled={}", id, existing.getIsEnabled());
        return toDto(existing);
    }

    // ========================================================================
    // 批量更新优先级
    // ========================================================================

    @Override
    @Transactional
    public void updatePriority(List<PriorityUpdateRequest> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("INVALID_PARAMETER: updates must not be empty");
        }
        for (PriorityUpdateRequest update : updates) {
            if (update.getId() == null || update.getPriority() == null) {
                throw new IllegalArgumentException("INVALID_PARAMETER: each update requires id and priority");
            }
            SceneRule existing = getRule(update.getId());
            existing.setPriority(update.getPriority());
            sceneRuleMapper.updateById(existing);
        }
        log.info("SceneRule priorities updated: count={}", updates.size());
    }

    // ========================================================================
    // 导出（v2 格式 JSON / YAML，供 Python 配置驱动引擎消费）
    // ========================================================================

    @Override
    public String exportRules(String format) {
        String fmt = (format == null || format.isBlank()) ? FORMAT_JSON : format.toLowerCase();
        if (!FORMAT_JSON.equals(fmt) && !FORMAT_YAML.equals(fmt)) {
            throw new IllegalArgumentException("INVALID_PARAMETER: format must be json|yaml, got=" + format);
        }

        // 仅导出启用中的规则（禁用规则不参与检测），按优先级升序
        List<SceneRule> enabledRules = getEnabledRules();
        Map<String, Object> export = buildExport(enabledRules);

        try {
            if (FORMAT_YAML.equals(fmt)) {
                return new Yaml().dump(export);
            }
            return objectMapper.writeValueAsString(export);
        } catch (Exception e) {
            throw new RuntimeException("SCENE_RULE_EXPORT_FAILED: " + e.getMessage(), e);
        }
    }

    @Override
    public Path writeRulesToTempDir() throws IOException {
        List<SceneRule> enabledRules = getEnabledRules();
        if (enabledRules.isEmpty()) {
            throw new IllegalStateException("SCENE_RULE_EXPORT_FAILED: no enabled rules to export");
        }

        Path dir = Files.createTempDirectory("dims_rules_");
        Set<String> usedFileNames = new HashSet<>();
        // 跨文件 features 去重：同名 feature 只保留首次出现（避免引擎全局注册冲突）
        Set<String> emittedFeatureNames = new HashSet<>();

        for (SceneRule rule : enabledRules) {
            String fileName = rule.getSceneType().replaceAll("[^a-zA-Z0-9_-]", "_") + ".yaml";
            if (!usedFileNames.add(fileName)) {
                throw new IOException(
                        "SCENE_RULE_EXPORT_FAILED: filename collision for scene_type "
                        + rule.getSceneType() + " → " + fileName
                        + " (sanitized name already used by another rule)");
            }

            // 过滤掉已在其他文件中输出过的 features（引擎全局注册，重复注册报错）
            List<Map<String, Object>> ownFeatures = nullToEmpty(rule.getFeatures());
            List<Map<String, Object>> dedupedFeatures = new ArrayList<>();
            for (Map<String, Object> feat : ownFeatures) {
                Object name = feat.get("name");
                String nameStr = name != null ? String.valueOf(name) : null;
                if (nameStr != null && emittedFeatureNames.add(nameStr)) {
                    dedupedFeatures.add(feat);
                } else if (nameStr != null) {
                    log.warn("Feature '{}' already registered by another rule, skipped in {}",
                            nameStr, fileName);
                }
            }

            Map<String, Object> single = buildExport(Collections.singletonList(rule));
            // 用去重后的 features 替换 buildExport 的输出
            if (!dedupedFeatures.isEmpty()) {
                single.put("features", dedupedFeatures);
            } else {
                single.remove("features");
            }

            String yaml = new Yaml().dump(single);
            Files.writeString(dir.resolve(fileName), yaml, StandardCharsets.UTF_8);
            log.info("SceneRule v2 YAML written: {}/{} ({} chars)",
                    dir, fileName, yaml.length());
        }
        log.info("SceneRule v2 rules dir prepared: {} ({} rules)", dir, enabledRules.size());
        return dir;
    }

    // ========================================================================
    // 全局阈值配置
    // ========================================================================

    @Override
    public List<SceneRuleConfig> getConfigs() {
        return sceneRuleConfigMapper.selectList(
                new LambdaQueryWrapper<SceneRuleConfig>().orderByAsc(SceneRuleConfig::getConfigKey));
    }

    @Override
    @Transactional
    public SceneRuleConfig updateConfig(String key, SceneRuleConfigUpdateRequest request) {
        if (request.getConfigValue() == null) {
            throw new IllegalArgumentException("INVALID_PARAMETER: config_value is required");
        }
        SceneRuleConfig config = sceneRuleConfigMapper.selectOne(
                new LambdaQueryWrapper<SceneRuleConfig>().eq(SceneRuleConfig::getConfigKey, key));
        if (config == null) {
            throw new SceneRuleConfigNotFoundException(key);
        }

        config.setConfigValue(request.getConfigValue());
        if (request.getUnit() != null) {
            config.setUnit(request.getUnit());
        }
        if (request.getDescription() != null) {
            config.setDescription(request.getDescription());
        }
        sceneRuleConfigMapper.updateById(config);
        log.info("SceneRuleConfig updated: key={}, value={}", key, config.getConfigValue());
        return config;
    }

    // ========================================================================
    // 导出构建（v2 结构，对齐 dims-python/scene_extraction/rules/*.yaml）
    // ========================================================================

    /**
     * 构建 v2 导出顶层 Map（LinkedHashMap 保证键序，SnakeYAML/JSON 输出稳定）。
     *
     * @param rules 参与导出的规则（全量导出 = 所有启用规则；单文件导出 = 单条规则）
     */
    private Map<String, Object> buildExport(List<SceneRule> rules) {
        Map<String, Object> export = new LinkedHashMap<>();
        // Integer 2：YAML 输出 version: 2，RuleLoader 校验 version == 2 通过
        export.put("version", 2);
        export.put("min_engine_version", resolveMinEngineVersion(rules));

        // features 段：合并去重（按 name，保持首次出现顺序）；空则不输出
        Map<String, Map<String, Object>> featureByName = new LinkedHashMap<>();
        for (SceneRule rule : rules) {
            for (Map<String, Object> feature : nullToEmpty(rule.getFeatures())) {
                Object name = feature.get("name");
                if (name != null && !featureByName.containsKey(String.valueOf(name))) {
                    featureByName.put(String.valueOf(name), stripNulls(feature));
                }
            }
        }
        if (!featureByName.isEmpty()) {
            export.put("features", new ArrayList<>(featureByName.values()));
        }

        List<Map<String, Object>> ruleMaps = new ArrayList<>(rules.size());
        for (SceneRule rule : rules) {
            ruleMaps.add(buildRuleMap(rule));
        }
        export.put("rules", ruleMaps);
        return export;
    }

    /**
     * 单条规则 → v2 导出 Map（键结构与 rules/cut_in.yaml 的 rules[] 元素一致）。
     */
    private Map<String, Object> buildRuleMap(SceneRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scene_type", rule.getSceneType());
        if (rule.getDescription() != null) {
            map.put("description", rule.getDescription());
        }
        map.put("confidence", rule.getConfidence() != null ? rule.getConfidence() : 0.85);
        map.put("priority", rule.getPriority() != null ? rule.getPriority() : 99);
        map.put("logic", rule.getLogic() != null ? rule.getLogic() : "all");
        if (rule.getEntityRoles() != null && !rule.getEntityRoles().isEmpty()) {
            map.put("entity_roles", rule.getEntityRoles());
        }
        // conditions 元素保留 value_type/params/negate/unit，null/空键省略
        map.put("conditions", stripNullsInList(rule.getConditions()));
        if (rule.getTimeWindow() != null) {
            map.put("time_window", rule.getTimeWindow());
        }
        // v2 对象数组（name/feature/aggregate/round），替代 v1 字符串数组
        if (rule.getOutputParameters() != null) {
            map.put("output_parameters", stripNullsInList(rule.getOutputParameters()));
        }
        if (rule.getPostprocess() != null && !rule.getPostprocess().isEmpty()) {
            map.put("postprocess", stripNullsInList(rule.getPostprocess()));
        }
        return map;
    }

    /**
     * 取启用规则中的最高版本门禁（引擎侧语义：engine >= min_engine_version 才加载）。
     */
    private String resolveMinEngineVersion(List<SceneRule> rules) {
        String max = "2.0";
        for (SceneRule rule : rules) {
            String v = rule.getMinEngineVersion();
            if (v == null || v.isBlank()) {
                continue;
            }
            if (compareVersions(v, max) > 0) {
                max = v;
            }
        }
        return max;
    }

    /** 简易 "x.y.z" 版本号比较（与 Python 引擎的 tuple 比较语义一致） */
    private int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = i < pa.length ? parseVersionPart(pa[i]) : 0;
            int y = i < pb.length ? parseVersionPart(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 列表内每个元素 Map 去除 null/空值键（引擎侧对这些键有缺省语义）。
     */
    private List<Map<String, Object>> stripNullsInList(List<Map<String, Object>> list) {
        if (list == null) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Map<String, Object> item : list) {
            result.add(stripNulls(item));
        }
        return result;
    }

    /**
     * 去除 null / 空字符串 / 空 Map / 空 List 的键（仅复制非空键，保持原顺序）。
     */
    private Map<String, Object> stripNulls(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object v = entry.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof String && ((String) v).isEmpty()) {
                continue;
            }
            if (v instanceof Map && ((Map<?, ?>) v).isEmpty()) {
                continue;
            }
            if (v instanceof List && ((List<?>) v).isEmpty()) {
                continue;
            }
            cleaned.put(entry.getKey(), v);
        }
        return cleaned;
    }

    private List<Map<String, Object>> nullToEmpty(List<Map<String, Object>> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private List<SceneRule> getEnabledRules() {
        return sceneRuleMapper.selectList(
                new LambdaQueryWrapper<SceneRule>()
                        .eq(SceneRule::getIsEnabled, true)
                        .orderByAsc(SceneRule::getPriority, SceneRule::getSceneType));
    }

    // ========================================================================
    // 私有辅助
    // ========================================================================

    /**
     * 按 ID 查询规则，不存在时抛类型化 404 异常
     */
    private SceneRule getRule(String id) {
        SceneRule rule = sceneRuleMapper.selectById(id);
        if (rule == null) {
            throw new SceneRuleNotFoundException(id);
        }
        return rule;
    }

    private void validateLogic(String logic) {
        if (logic != null && !"all".equals(logic) && !"any".equals(logic)) {
            throw new IllegalArgumentException("INVALID_PARAMETER: logic must be 'all' or 'any'");
        }
    }

    /** DTO 实体角色声明 → 实体存储形态（Map<String, Object>，值反序列化后为普通 Map） */
    private Map<String, Object> toEntityRolesMap(Map<String, SceneRuleDTO.EntityRoleDTO> roles) {
        if (roles == null) {
            return null;
        }
        return objectMapper.convertValue(roles, new TypeReference<Map<String, Object>>() {});
    }

    /** DTO 输出参数数组 → 实体存储形态（List<Map<String, Object>>） */
    private List<Map<String, Object>> toOutputParamsList(List<SceneRuleDTO.OutputParamDTO> params) {
        if (params == null) {
            return null;
        }
        return objectMapper.convertValue(params, new TypeReference<List<Map<String, Object>>>() {});
    }

    /** DTO 后处理声明数组 → 实体存储形态 */
    private List<Map<String, Object>> toPostprocessList(List<SceneRuleDTO.PostProcessSpecDTO> specs) {
        if (specs == null) {
            return null;
        }
        return objectMapper.convertValue(specs, new TypeReference<List<Map<String, Object>>>() {});
    }

    /** DTO 复合特征数组 → 实体存储形态 */
    private List<Map<String, Object>> toFeaturesList(List<SceneRuleDTO.FeatureSpecDTO> features) {
        if (features == null) {
            return null;
        }
        return objectMapper.convertValue(features, new TypeReference<List<Map<String, Object>>>() {});
    }

    /** 实体 entity_roles → DTO（值 Map → EntityRoleDTO） */
    private Map<String, SceneRuleDTO.EntityRoleDTO> toEntityRolesDto(Map<String, Object> roles) {
        if (roles == null) {
            return null;
        }
        return objectMapper.convertValue(roles, new TypeReference<Map<String, SceneRuleDTO.EntityRoleDTO>>() {});
    }

    /** 实体 output_parameters → DTO（Map → OutputParamDTO） */
    private List<SceneRuleDTO.OutputParamDTO> toOutputParamsDto(List<Map<String, Object>> params) {
        if (params == null) {
            return null;
        }
        return objectMapper.convertValue(params, new TypeReference<List<SceneRuleDTO.OutputParamDTO>>() {});
    }

    /** 实体 postprocess → DTO（Map → PostProcessSpecDTO） */
    private List<SceneRuleDTO.PostProcessSpecDTO> toPostprocessDto(List<Map<String, Object>> specs) {
        if (specs == null) {
            return null;
        }
        return objectMapper.convertValue(specs, new TypeReference<List<SceneRuleDTO.PostProcessSpecDTO>>() {});
    }

    /** 实体 features → DTO（Map → FeatureSpecDTO） */
    private List<SceneRuleDTO.FeatureSpecDTO> toFeaturesDto(List<Map<String, Object>> features) {
        if (features == null) {
            return null;
        }
        return objectMapper.convertValue(features, new TypeReference<List<SceneRuleDTO.FeatureSpecDTO>>() {});
    }

    /**
     * Entity → 响应 DTO 转换
     */
    private SceneRuleDTO toDto(SceneRule rule) {
        SceneRuleDTO dto = new SceneRuleDTO();
        dto.setId(rule.getId());
        dto.setSceneType(rule.getSceneType());
        dto.setDescription(rule.getDescription());
        dto.setReference(rule.getReference());
        dto.setPriority(rule.getPriority());
        dto.setIsEnabled(rule.getIsEnabled());
        dto.setVersion(rule.getVersion());
        dto.setLogic(rule.getLogic());
        dto.setConfidence(rule.getConfidence());
        dto.setEntityRoles(toEntityRolesDto(rule.getEntityRoles()));
        dto.setConditions(rule.getConditions());
        dto.setTimeWindow(rule.getTimeWindow());
        dto.setOutputParameters(toOutputParamsDto(rule.getOutputParameters()));
        dto.setPostprocess(toPostprocessDto(rule.getPostprocess()));
        dto.setFeatures(toFeaturesDto(rule.getFeatures()));
        dto.setMinEngineVersion(rule.getMinEngineVersion());
        dto.setOutputParams(rule.getOutputParams());
        dto.setCreatedAt(rule.getCreatedAt());
        dto.setUpdatedAt(rule.getUpdatedAt());
        return dto;
    }
}
