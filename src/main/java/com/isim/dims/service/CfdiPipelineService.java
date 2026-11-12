package com.isim.dims.service;

/**
 * CFDI 数据包导入流水线 Service
 *
 * 负责 CFDI 数据包上传后的异步完整流水线执行。
 * 提取为独立 Bean 以确保 @Async 通过 Spring AOP 代理正常生效。
 *
 * 流水线步骤：
 *   1. CONVERTING — 解压 tar.gz → Docker 转换 → 复制 bag/h265
 *   2. PARSING — 复用 IngestionPipelineService
 *   3. TAGGING — 复用 IngestionPipelineService
 *   4. READY — 标记切片可用
 * @feature: cfdi
 */
public interface CfdiPipelineService {

    /**
     * 异步执行 CFDI 导入流水线
     *
     * @param sliceId 切片 UUID
     * @param tarRef  tar.gz 存储引用（local 模式可为绝对路径或相对 key，minio 模式为相对 key）
     */
    void processCfdiPipeline(String sliceId, String tarRef);
}
