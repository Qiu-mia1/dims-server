package com.isim.dims.schedule;

import lombok.Data;

import java.time.Instant;

/**
 * 已下载的 BOS 文件记录。
 *
 * <p>用于将「已下载最新文件的信息」写入 JSON 记录文件，
 * 字段采用 camelCase，序列化时遵循全局 Jackson 命名策略（snake_case）。</p>
 *
 * @feature: ingestion
 */
@Data
public class DownloadRecord {

    /**
     * BOS 对象 Key，如 "bags/2024/data.bag"
     */
    private String objectKey;

    /**
     * 本地落地文件绝对路径
     */
    private String localPath;

    /**
     * 对象大小（字节）
     */
    private Long size;

    /**
     * BOS 上对象的最后修改时间（即上传完成时间）
     */
    private Instant lastModified;

    /**
     * 本地下载完成时间
     */
    private Instant downloadedAt;
}