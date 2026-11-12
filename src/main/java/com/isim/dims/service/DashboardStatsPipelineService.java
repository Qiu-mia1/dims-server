package com.isim.dims.service;

import com.isim.dims.entity.DashboardStatsTask;

/**
 * 驾驶舱统计提取流水线 Service 接口.
 *
 * <p>在 bag 导入 READY 后异步提取驾驶舱所需的统计指标
 * （车辆型号、里程、车速、场景类型、功能状态等）并写入
 * {@code slice_stats} 表。</p>
 *
 * @feature: dashboard
 */
public interface DashboardStatsPipelineService {

    /**
     * 触发统计提取（由 IngestionPipelineServiceImpl Step 3.5 调用）.
     *
     * <p>创建 PENDING 任务并委派异步流水线。失败不影响切片 READY 状态。</p>
     *
     * @param sliceId 切片 ID
     */
    void triggerExtraction(String sliceId);

    /**
     * 异步执行统计提取（@Async 经 AOP 代理生效）.
     *
     * @param task 已创建的 PENDING 任务
     */
    void processExtraction(DashboardStatsTask task);
}
