package com.isim.dims.exception;

/**
 * 存储层通用异常（MinIO 迁移）
 *
 * <p>本地文件系统 → StorageService 抽象后，所有存储 I/O 失败统一抛出本异常。
 * 消息保持 "CODE: detail" 前缀格式，兼容既有错误码约定与日志检索：</p>
 * <ul>
 *   <li>UPLOAD_FAILED: 写入失败</li>
 *   <li>READ_FAILED: 读取失败</li>
 *   <li>DELETE_FAILED: 删除失败</li>
 *   <li>STORAGE_INVALID_KEY: key 不合法（前缀未知 / 越界 / 空白）</li>
 * </ul>
 * @feature: infra
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
