package com.isim.dims.service;

/**
 * 回放 rosdata.json 提取服务（从导入流水线抽取的独立 service）。
 *
 * <p>两条入口路径共用同一提取实现，职责边界如下：</p>
 * <ul>
 *   <li>{@link #extractOnIngestion(String, String)} —— 导入流水线主路径
 *       （{@code IngestionPipelineServiceImpl.processParsePipeline} Step 1 早期）。
 *       软失败语义：异常仅 warn，不向上抛出，与原内联实现一致
 *       —— 回放数据属辅助可视化产物，失败不应阻塞已完成/在跑的导入链路。</li>
 *   <li>{@link #reExtractBySliceId(String)} —— {@code ReplayRosDataScheduler}
 *       定时重跑入口。需要先从 DB 加载切片获取 bag 路径，并按现有字段
 *       （{@code data_slice.updated_at} / {@code error_msg}）做轻量记账。</li>
 * </ul>
 *
 * <p><b>约束</b>（2026-09 用户反馈）：不新增 DB 字段（如 {@code replay_status} /
 * {@code replay_checked_at}），状态用现有 {@code data_slice.updated_at} /
 * {@code error_msg} 表达。重跑路径仅在失败时写 {@code error_msg}，成功后
 * 写 {@code updated_at}（MyBatis-Plus {@code @TableField(fill = INSERT_UPDATE)}
 * 自动填充，或显式 {@code setUpdatedAt(now)}）。</p>
 */
public interface ReplayRosDataService {

    /**
     * 导入流水线主路径：提取回放 rosdata.json 并落盘。
     *
     * <p>任何异常均吞掉并 warn，调用方无需 try/catch —— 与原
     * {@code IngestionPipelineServiceImpl.extractReplayRosData} 软失败语义一致。</p>
     *
     * @param sliceId  切片 ID（同时作为产物存储 key 的前缀）
     * @param localBag bag 文件本地路径（已由 {@code storageAccess.materializeLocal} 物化）
     */
    void extractOnIngestion(String sliceId, String localBag);

    /**
     * 定时任务重跑入口：从 DB 加载切片获取 bag，重跑回放提取，更新 minio 产物。
     *
     * <p>失败时仅更新 {@code data_slice.error_msg}（成功时清空，避免脏记），
     * 同时刷新 {@code updated_at}（MyBatis-Plus FieldFill 自动写入）。
     * 调用方无需 try/catch —— 内部已捕获并记账。</p>
     *
     * @param sliceId 待重跑切片 ID
     */
    void reExtractBySliceId(String sliceId);
}