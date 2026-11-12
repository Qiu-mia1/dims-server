package com.isim.dims.service;

import com.isim.dims.dto.TaskResponse;
import com.isim.dims.entity.IngestionTask;

/**
 * 导入任务 Service 接口
 *
 * 负责查询数据切片导入过程的异步任务进度。
 * 任务状态流转：UPLOADING -> PARSING -> TAGGING -> VIDEO_EXTRACTING -> STATS_EXTRACTING -> SCENE_EXTRACTING -> READY / ERROR
 * @feature: ingestion
 */
public interface IngestionService {

    /**
     * 查询导入任务进度和状态
     *
     * @param taskId 导入任务 UUID
     * @return 导入任务响应（含 status_history）
     * @throws RuntimeException 如果导入任务不存在
     */
    TaskResponse getTask(String taskId);

    /**
     * 查询导入任务实体（内部使用）
     *
     * @param taskId 导入任务 UUID
     * @return 导入任务实体
     * @throws RuntimeException 如果导入任务不存在
     */
    IngestionTask getTaskEntity(String taskId);

    /**
     * 更新任务状态并记录状态变更历史
     *
     * 事务保证 task UPDATE + history INSERT 的原子性。
     * 各流水线 Service 统一调用此方法，消除重复代码。
     *
     * @param task    任务实体（将被 mutate）
     * @param status  新状态
     * @param progress 进度值 0-100
     */
    void updateTaskStatus(IngestionTask task, String status, int progress);
}
