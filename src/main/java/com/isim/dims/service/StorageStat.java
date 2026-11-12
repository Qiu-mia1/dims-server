package com.isim.dims.service;

/**
 * 存储对象元信息（MinIO 迁移）
 *
 * @param size        字节大小
 * @param contentType MIME 类型（本地模式无法从文件系统推断时为 null，由调用方兜底）
 */
public record StorageStat(long size, String contentType) {
}
