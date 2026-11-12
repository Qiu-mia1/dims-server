package com.isim.dims.exception;

/**
 * 存储对象不存在异常（MinIO 迁移）
 *
 * <p>对不存在（或不可访问）的存储 key 执行读取/stat 时抛出，
 * Controller 层据此映射 404。消息前缀 STORAGE_NOT_FOUND。</p>
 * @feature: infra
 */
public class StorageNotFoundException extends StorageException {

    public StorageNotFoundException(String key) {
        super("STORAGE_NOT_FOUND: " + key);
    }
}
