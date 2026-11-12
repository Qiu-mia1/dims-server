package com.isim.dims.service;

import com.isim.dims.dto.PriorityUpdateRequest;
import com.isim.dims.dto.SceneRuleConfigUpdateRequest;
import com.isim.dims.dto.SceneRuleCreateRequest;
import com.isim.dims.dto.SceneRuleDTO;
import com.isim.dims.dto.SceneRuleUpdateRequest;
import com.isim.dims.entity.SceneRuleConfig;

import java.util.List;

/**
 * 场景规则 Service 接口
 *
 * 负责场景判断规则的 CRUD、启用/禁用切换、优先级批量调整、
 * 规则/阈值导出（供 Python 规则引擎消费）及全局阈值配置管理。
 * @feature: ingestion
 */
public interface SceneRuleService {

    /**
     * 规则列表，支持按启用状态过滤
     *
     * @param enabled 是否启用；null 返回全部
     * @return 规则 DTO 列表（按 priority 升序、scene_type 升序）
     */
    List<SceneRuleDTO> list(Boolean enabled);

    /**
     * 获取单条规则
     *
     * @param id 规则 UUID
     * @return 规则 DTO
     * @throws com.isim.dims.exception.SceneRuleNotFoundException 如果规则不存在
     */
    SceneRuleDTO get(String id);

    /**
     * 新增规则
     *
     * @param request 创建请求（scene_type / conditions 必填；time_window /
     *                output_parameters 建议必填，v1 遗留 output_params 兼容接收）
     * @return 创建后的规则 DTO
     * @throws IllegalArgumentException 参数缺失时
     */
    SceneRuleDTO create(SceneRuleCreateRequest request);

    /**
     * 更新规则（仅更新非空字段）
     *
     * @param id      规则 UUID
     * @param request 更新请求
     * @return 更新后的规则 DTO
     * @throws com.isim.dims.exception.SceneRuleNotFoundException 如果规则不存在
     */
    SceneRuleDTO update(String id, SceneRuleUpdateRequest request);

    /**
     * 启用/禁用切换
     *
     * @param id 规则 UUID
     * @return 切换后的规则 DTO
     * @throws com.isim.dims.exception.SceneRuleNotFoundException 如果规则不存在
     */
    SceneRuleDTO toggle(String id);

    /**
     * 批量更新优先级
     *
     * @param updates 优先级更新列表 [{id, priority}, ...]，事务内完成
     * @throws IllegalArgumentException              列表为空时
     * @throws com.isim.dims.exception.SceneRuleNotFoundException 任一规则不存在时
     */
    void updatePriority(List<PriorityUpdateRequest> updates);

    /**
     * 导出规则配置（v2 格式，供 Python 配置驱动引擎消费）
     *
     * 结构与 dims-python/scene_extraction/rules/*.yaml 等价，RuleLoader.load() 可直接加载：
     * <pre>
     * version: 2
     * min_engine_version: "2.0"     # 取启用规则中的最高版本门禁
     * features: [...]               # 合并所有启用规则的 features 段（按 name 去重）
     * rules: [...]                  # 每条含 scene_type / confidence / priority /
     *                               # logic / entity_roles / conditions /
     *                               # time_window / output_parameters / postprocess
     * </pre>
     * 仅包含启用中的规则，按 priority 升序、scene_type 升序。
     * v1 的 output_params（字符串数组）已升级为 v2 的 output_parameters
     * （对象数组，含 name / feature / aggregate / round），消费方须按 v2 解析。
     *
     * @param format json（默认）| yaml
     * @return 序列化后的配置字符串
     * @throws IllegalArgumentException format 非法时
     */
    String exportRules(String format);

    /**
     * 将启用中的规则导出为 v2 YAML 写入临时目录，返回规则目录路径。
     *
     * 供 Pipeline 在调用 Python scene_detector.py 前准备规则文件：
     * 每个启用规则生成一个 {tempDir}/{scene_type}.yaml（含 version /
     * min_engine_version / features / rules 段），Python 端
     * RuleLoader.load(rulesPath) 按目录 glob 加载。
     *
     * @return 规则目录的 Path（每个 scene_type 一个 .yaml 文件）；
     *         目录清理由调用方负责（本方法不自动删除）
     * @throws java.io.IOException 临时目录创建或文件写入失败时
     */
    java.nio.file.Path writeRulesToTempDir() throws java.io.IOException;

    /**
     * 获取全部阈值配置
     *
     * @return 阈值配置列表（按 config_key 升序）
     */
    List<SceneRuleConfig> getConfigs();

    /**
     * 更新阈值配置（仅更新非空字段）
     *
     * @param key     阈值配置 key（如 lane_width_typical）
     * @param request 更新请求（config_value 必填）
     * @return 更新后的阈值配置
     * @throws com.isim.dims.exception.SceneRuleConfigNotFoundException 如果配置不存在
     */
    SceneRuleConfig updateConfig(String key, SceneRuleConfigUpdateRequest request);
}
