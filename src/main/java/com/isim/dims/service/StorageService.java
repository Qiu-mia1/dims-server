package com.isim.dims.service;

import com.isim.dims.exception.StorageException;
import com.isim.dims.exception.StorageNotFoundException;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * 存储服务抽象（本地文件系统 → MinIO 迁移）
 *
 * <p>业务层一律通过 {@link com.isim.dims.service.impl.StorageAccess} 门面访问存储，
 * 不直接依赖本接口或任一实现。key 规范见 {@link com.isim.dims.util.StorageKeys}。</p>
 *
 * <p>实现：
 * <ul>
 *   <li>{@code LocalStorageService} — 本地文件系统（默认，key → StorageConfig 目录映射，
 *       兼容存量绝对路径 file_path）</li>
 *   <li>{@code MinioStorageService} — MinIO 对象存储（dims.storage.type=minio）</li>
 * </ul>
 * 条件装配：{@code dims.storage.type} 为 local / minio 时分别装配。</p>
 *
 * <p>异常约定：key 不存在时读取/stat 抛 {@link StorageNotFoundException}；
 * 其余 I/O 失败抛 {@link StorageException}（UPLOAD_FAILED / READ_FAILED / DELETE_FAILED）。</p>
 * @feature: infra
 */
public interface StorageService {

    /**
     * key 对应对象是否存在
     */
    boolean exists(String key);

    /**
     * 对象元信息；不存在抛 {@link StorageNotFoundException}
     */
    StorageStat stat(String key);

    /**
     * 写入对象（流式）；父级路径不存在时自动创建
     *
     * @param size        字节大小（本地实现可忽略）
     * @param contentType MIME 类型，可为 null
     */
    void put(String key, InputStream in, long size, String contentType);

    /**
     * 写入本地文件（本地实现 Files.move，MinIO 实现经 S3TransferManager 自动分片）
     */
    void put(String key, Path localFile, String contentType);

    /**
     * 下载对象到本地文件（覆盖已存在文件）
     */
    void download(String key, Path destFile);

    /**
     * 打开对象读取流；不存在抛 {@link StorageNotFoundException}
     */
    InputStream open(String key);

    /**
     * 打开对象读取流并跳过 [0, start)，最多读取 (end - start + 1) 字节
     * （MinIO 实现为服务端裁剪，本地实现为 skipNBytes）
     */
    InputStream open(String key, long start, long end);

    /**
     * 删除对象（幂等：不存在时静默成功）
     */
    void delete(String key);

    /**
     * 删除前缀下的全部对象（目录语义：前缀指向的“目录”本身不删除，仅删内容）
     */
    void deleteByPrefix(String prefix);

    /**
     * 列出前缀下的全部对象 key（"/" 分隔、含前缀本身），按字典序；无对象时返回空列表
     */
    List<String> list(String prefix);

    /**
     * 将相对 key 解析为本地文件系统路径（仅 LOCAL 实现支持）。
     * MINIO 实现抛 {@link UnsupportedOperationException}——远程对象必须先
     * 经 {@link com.isim.dims.service.impl.StorageAccess#materializeLocal(String)} 落盘。
     */
    default Path resolveLocalPath(String key) {
        throw new UnsupportedOperationException(
                "resolveLocalPath is only supported in local storage mode; use StorageAccess#materializeLocal");
    }
}
