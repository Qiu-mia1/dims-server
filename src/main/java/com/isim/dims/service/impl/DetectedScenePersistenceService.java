package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.isim.dims.entity.DetectedScene;
import com.isim.dims.mapper.DetectedSceneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * detected_scene 表持久化服务
 *
 * <p>独立 Bean 保证 {@code @Transactional} 经 Spring 代理生效
 * （SceneExtractionPipelineServiceImpl 中为私有方法自调用，注解会被绕过）。</p>
 *
 * <p>场景提取重跑/重试时先清空该切片旧场景数据，再批量插入新数据，
 * 两者在同一事务中：任一步失败整体回滚，避免重复数据或半截数据。</p>
 *
 * @feature: ingestion
 */
@Service
@RequiredArgsConstructor
public class DetectedScenePersistenceService {

    private final DetectedSceneMapper sceneMapper;

    /**
     * 事务性替换切片场景数据（DELETE 旧数据 + 批量 INSERT 新数据）
     *
     * @param sliceId 切片 UUID
     * @param scenes  本次任务解析出的场景列表（空列表 = 清空旧数据）
     */
    @Transactional
    public void replaceSliceScenes(String sliceId, List<DetectedScene> scenes) {
        sceneMapper.delete(new LambdaQueryWrapper<DetectedScene>()
                .eq(DetectedScene::getSliceId, sliceId));
        if (scenes != null && !scenes.isEmpty()) {
            Db.saveBatch(scenes);
        }
    }
}
