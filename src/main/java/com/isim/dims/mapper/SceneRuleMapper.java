package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.SceneRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 场景规则 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 scene_rule 表的完整 CRUD。
 * JSON 列（conditions / time_window / output_params）的读写
 * 由实体上的 {@link com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler} 处理。
 * @feature: ingestion
 */
@Mapper
public interface SceneRuleMapper extends BaseMapper<SceneRule> {
}
