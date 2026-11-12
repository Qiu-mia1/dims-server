package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.SceneRuleConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 场景规则阈值配置 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 scene_rule_config 表的完整 CRUD。
 * @feature: ingestion
 */
@Mapper
public interface SceneRuleConfigMapper extends BaseMapper<SceneRuleConfig> {
}
