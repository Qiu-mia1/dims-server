package com.isim.dims.exception;

/**
 * 场景规则不存在异常（Review W1 模式）
 *
 * 替代 Controller 层以字符串前缀（msg.startsWith("SCENE_RULE_NOT_FOUND:")）
 * 判定 404 的脆弱写法：Service 抛出类型化异常，Controller 通过类型捕获精确返回 404。
 *
 * 消息保持 "SCENE_RULE_NOT_FOUND: {ruleId}" 前缀格式，兼容既有的错误码约定与日志检索。
 * @feature: ingestion
 */
public class SceneRuleNotFoundException extends RuntimeException {

    /**
     * @param ruleId 不存在的规则 UUID
     */
    public SceneRuleNotFoundException(String ruleId) {
        super("SCENE_RULE_NOT_FOUND: " + ruleId);
    }
}
