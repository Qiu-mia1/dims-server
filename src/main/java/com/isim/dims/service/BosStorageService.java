package com.isim.dims.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 百度 BOS 对象存储下载服务
 *
 * <p>通过 AWS S3 SDK V2 的 S3 兼容协议访问 BOS（JDK 17 兼容），
 * 支持单 Object 下载与按前缀目录批量下载（自动分页，保持目录结构）。</p>
 *
 * <p>典型用法：从 BOS 恢复整包数据到本地 storage 目录后走既有导入流水线。</p>
 * @feature: ingestion
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "dims.storage.type", havingValue = "bos")
@RequiredArgsConstructor
public class BosStorageService {

    private final S3Client s3Client;
    private final S3TransferManager transferManager;

    @Value("${bos.bucket}")
    private String bucket;

    /**
     * 下载单个 Object 到本地文件。
     * 小文件逐个下载，大文件自动使用分片下载。
     *
     * @param objectKey BOS 上的对象 Key，如 "bags/2024/data.bag"
     * @param localFile 本地目标文件路径，如 /data/restored/data.bag
     */
    public void downloadObject(String objectKey, Path localFile) {
        try {
            Files.createDirectories(localFile.getParent());
        } catch (IOException e) {
            throw new RuntimeException("创建本地目录失败: " + localFile.getParent(), e);
        }

        DownloadFileRequest request = DownloadFileRequest.builder()
                .getObjectRequest(b -> b.bucket(bucket).key(objectKey))
                .destination(localFile)
                .build();

        transferManager.downloadFile(request).completionFuture().join();
        log.info("下载完成: {} -> {}", objectKey, localFile);
    }

    /**
     * 下载 BOS 上指定前缀的所有 Object 到本地目录，保持目录结构。
     *
     * @param bosPrefix BOS 上的前缀，如 "bags/2024/"（不以 / 开头）
     * @param localDir  本地目标目录，如 /data/restored/
     * @return 下载的文件数量
     */
    public int downloadDirectory(String bosPrefix, Path localDir) {
        String prefix = bosPrefix.endsWith("/") ? bosPrefix : bosPrefix + "/";
        int count = 0;
        String continuationToken = null;

        do {
            ListObjectsV2Response response = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .maxKeys(1000)
                            .continuationToken(continuationToken)
                            .build()
            );

            for (S3Object obj : response.contents()) {
                String key = obj.key();

                // 跳过目录占位符（空对象）
                if (key.endsWith("/") && obj.size() == 0) {
                    continue;
                }

                String relativePath = key.substring(prefix.length());
                Path localFile = localDir.resolve(relativePath);

                try {
                    downloadObject(key, localFile);
                    count++;
                } catch (Exception e) {
                    log.error("下载失败: {}", key, e);
                    throw new RuntimeException("下载失败: " + key, e);
                }
            }

            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;

        } while (continuationToken != null);

        log.info("目录下载完成: {} ({} 个文件)", bosPrefix, count);
        return count;
    }
}
