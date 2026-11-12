package com.isim.dims.service.impl;

import com.isim.dims.dto.TaskResponse;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.entity.IngestionTaskStatusHistory;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.IngestionTaskStatusHistoryMapper;
import com.isim.dims.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 导入任务 Service 实现
 *
 * 提供导入任务进度查询功能。
 * 任务状态流转：UPLOADING -> PARSING -> TAGGING -> READY / ERROR
 * @feature: ingestion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionServiceImpl implements IngestionService {

    private final IngestionTaskMapper ingestionTaskMapper;
    private final IngestionTaskStatusHistoryMapper ingestionTaskStatusHistoryMapper;

    @Override
    public TaskResponse getTask(String taskId) {
        IngestionTask task = getTaskEntity(taskId);

        // 查询状态变更历史
        List<IngestionTaskStatusHistory> historyEntities =
                ingestionTaskStatusHistoryMapper.selectByTaskId(taskId);

        // 转换为 TaskResponse
        TaskResponse resp = new TaskResponse();
        resp.setTaskId(task.getId());
        resp.setSliceId(task.getSliceId());
        resp.setStatus(task.getStatus());
        resp.setProgress(task.getProgress());
        resp.setErrorMsg(task.getErrorMsg());
        resp.setStatusHistory(historyEntities.stream()
                .map(h -> {
                    TaskResponse.StatusHistoryItem item = new TaskResponse.StatusHistoryItem();
                    item.setStatus(h.getStatus());
                    item.setTimestamp(h.getCreatedAt() != null
                            ? h.getCreatedAt().atZone(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            : null);
                    return item;
                })
                .collect(Collectors.toList()));

        return resp;
    }

    @Override
    public IngestionTask getTaskEntity(String taskId) {
        IngestionTask task = ingestionTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("TASK_NOT_FOUND: " + taskId);
        }
        return task;
    }

    @Override
    @Transactional
    public void updateTaskStatus(IngestionTask task, String status, int progress) {
        task.setStatus(status);
        task.setProgress(progress);
        ingestionTaskMapper.updateById(task);

        // 记录状态变更历史（与 UPDATE 在同一事务中）
        IngestionTaskStatusHistory history = new IngestionTaskStatusHistory();
        history.setTaskId(task.getId());
        history.setStatus(status);
        history.setProgress(progress);
        ingestionTaskStatusHistoryMapper.insert(history);

        log.debug("Task status updated: taskId={}, status={}, progress={}", task.getId(), status, progress);
    }
}
