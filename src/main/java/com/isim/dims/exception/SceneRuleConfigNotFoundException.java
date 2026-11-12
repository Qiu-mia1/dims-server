package com.isim.dims.exception;

/**
 * 场景规则阈值配置不存在异常（Review W1 模式）
 *
 * 替代字符串前缀判定的 404 写法，与 {@link SceneRuleNotFoundException} 同模式。
 *
 * 消息保持 "SCENE_RULE_CONFIG_NOT_FOUND: {configKey}" 前缀格式。
 * @feature: ingestion
 */
public class SceneRuleConfigNotFoundException extends RuntimeException {

    /**
     * @param configKey 不存在的阈值配置 key
     */
    public SceneRuleConfigNotFoundException(String configKey) {
        super("SCENE_RULE_CONFIG_NOT_FOUND: " + configKey);
    }
}
