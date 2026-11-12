package com.isim.dims.exception;

/**
 * 数据已归档不可访问异常（MinIO 迁移）
 *
 * <p>MINIO 模式下，对 storage_type=LOCAL 的存量记录执行数据内容读取时抛出
 * （存量 19GB 本地数据不迁移，仅归档）。Controller 层映射 <b>410 Gone</b>：
 * <pre>
 * { "error": { "code": "DATA_ARCHIVED",
 *              "message": "DATA_ARCHIVED: &lt;entity_type&gt; &lt;id&gt; is archived and not accessible in current storage mode",
 *              "trace_id": "&lt;uuid&gt;" } }
 * </pre>
 * 本地模式下永不拦截（local 模式数据就在本地磁盘）。</p>
 * @feature: infra
 */
public class StorageArchivedException extends StorageException {

    public StorageArchivedException(String entityType, String id) {
        super("DATA_ARCHIVED: " + entityType + " " + id
                + " is archived and not accessible in current storage mode");
    }
}
