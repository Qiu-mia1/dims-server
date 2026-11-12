package com.isim.dims.service;

import com.isim.dims.dto.ExportRequest;
import com.isim.dims.entity.ExportTask;

/**
 * 数据导出流水线 Service
 *
 * 负责按时间窗口和 Topic 列表异步裁剪 bag 文件。
 * 提取为独立 Bean 以确保 @Async 通过 Spring AOP 代理正常生效。
 * @feature: export
 */
public interface ExportPipelineService {

    /**
     * 异步执行导出流水线
     *
     * @param task        已持久化的 ExportTask 对象（传入对象而非 ID，避免事务未提交时的竞态条件）
     * @param bagFilePath bag 文件绝对路径
     * @param request     导出请求参数
     */
    void processExportPipeline(ExportTask task, String bagFilePath,
                               ExportRequest request);
}
