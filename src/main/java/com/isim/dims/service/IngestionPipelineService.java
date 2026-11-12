package com.isim.dims.service;

/**
 * 数据导入流水线 Service
 *
 * 负责 ROS1 bag 文件上传后的异步解析流水线执行。
 * 提取为独立 Bean 以确保 @Async 通过 Spring AOP 代理正常生效。
 *
 * 流水线步骤：
 *   1. PARSING — 调用 Python parse_rosbag.py 解析元数据
 *   2. TAGGING — 调用 Python auto_tag.py 自动打标
 *   3. VIDEO_EXTRACTING — 相机视频段提取（等待完成）
 *   4. STATS_EXTRACTING — 驾驶舱统计提取（等待完成）
 *   5. SCENE_EXTRACTING — 场景提取（等待完成）
 *   6. READY   — 前三步全部成功才标记切片可用
 * @feature: ingestion
 */
public interface IngestionPipelineService {

    /**
     * 异步执行解析流水线
     *
     * @param sliceId  切片 UUID
     * @param filePath bag 文件绝对路径
     */
    void processParsePipeline(String sliceId, String filePath);
}
