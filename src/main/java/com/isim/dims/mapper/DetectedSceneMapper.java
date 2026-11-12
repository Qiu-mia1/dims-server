package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.DetectedScene;
import org.apache.ibatis.annotations.Mapper;

/**
 * 已检测场景 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 detected_scene 表的完整 CRUD。
 * 切片场景分页查询通过 LambdaQueryWrapper + selectCount/selectList 完成
 * （见 SceneExtractionPipelineServiceImpl）。
 * @feature: ingestion
 */
@Mapper
public interface DetectedSceneMapper extends BaseMapper<DetectedScene> {
}
