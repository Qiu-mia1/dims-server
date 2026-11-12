package com.isim.dims.service.impl;

import com.baidubce.BceClientException;
import com.baidubce.BceServiceException;
import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.model.BosObjectSummary;
import com.baidubce.services.bos.model.ListObjectsResponse;
import com.baidubce.services.bos.model.ObjectMetadata;
import com.isim.dims.exception.StorageException;
import com.isim.dims.exception.StorageNotFoundException;
import com.isim.dims.service.StorageService;
import com.isim.dims.service.StorageStat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedUpload;
import software.amazon.awssdk.transfer.s3.model.Upload;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * MinIO 对象存储实现（MinIO 迁移，dims.storage.type=minio）
 *
 * <p>全部经 AWS S3 SDK V2 访问（MinIO 完全兼容 S3 协议）：
 * <ul>
 *   <li>大文件写走 {@link S3TransferManager#upload}（自动分片）</li>
 *   <li>Range 读走 GetObject 原生 {@code range} 参数（服务端裁剪，不下载全量）</li>
 *   <li>deleteByPrefix / list 走 ListObjectsV2 分页</li>
 * </ul>
 * 拒绝绝对路径 / ".." / 空白 key；客户端 Bean 经 @Qualifier 注入
 * MinioConfig 注册的 minioS3Client / minioS3TransferManager。</p>
 *
 * @feature: infra
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dims.storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private final S3Client s3Client;
    private final S3TransferManager transferManager;
    private final String bucket;
    private final String endpoint;

    @Autowired
    private BosClient bosClient;

    public MinioStorageService(@Qualifier("minioS3Client") S3Client s3Client,
                               @Qualifier("minioS3TransferManager") S3TransferManager transferManager,
                               @Value("${minio.bucket}") String bucket,
                               @Value("${minio.endpoint}") String endpoint) {
        this.s3Client = s3Client;
        this.transferManager = transferManager;
        this.bucket = bucket;
        this.endpoint = endpoint;
    }

    /**
     * 是否百度 BOS（endpoint 命中 *bcebos.com）。
     *
     * <p>百度 BOS 的 S3 兼容端点（如 su.bcebos.com）用 AWS S3 SDK 以虚拟主机风格
     * （pathstyleAccess=false）+ 无 scheme 的 endpointOverride 组装请求时，
     * HEAD / Range GET 常因地址解析/签名不符而失败（视频 <video> 加载 404/403）。
     * 因此对象读取类操作改走原生百度 {@link BosClient}（MinioConfig 已在同源
     * AK/SK + 同桶前缀创建，落地正确签名）。真 MinIO 保持 AWS S3 路径不变。</p>
     */
    private boolean isBaiduBce() {
        return endpoint != null && endpoint.toLowerCase().contains("bcebos.com");
    }

    /**
     * 解包异常链，探测 BOS 服务端 HTTP 404（对象不存在）。
     */
    private boolean isNotFound(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof BceServiceException se && se.getStatusCode() == 404) {
                return true;
            }
        }
        return false;
    }

    private String requireRelativeKey(String key) {
        if (key == null || key.isBlank()
                || key.startsWith("/") || key.contains("..")) {
            throw new StorageException("STORAGE_INVALID_KEY: key must be a relative storage key: " + key);
        }
        return key;
    }

    @Override
    public boolean exists(String key) {
        requireRelativeKey(key);
        if (isBaiduBce()) {
            try {
                return bosClient.doesObjectExist(bucket, key);
            } catch (BceClientException e) {
                throw new StorageException("READ_FAILED: head " + key, e);
            }
        }
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (SdkException e) {
            throw new StorageException("READ_FAILED: head " + key, e);
        }
    }

    @Override
    public StorageStat stat(String key) {
        requireRelativeKey(key);
        if (isBaiduBce()) {
            try {
                ObjectMetadata md = bosClient.getObjectMetadata(bucket, key);
                return new StorageStat(md.getContentLength(), md.getContentType());
            } catch (BceClientException e) {
                if (isNotFound(e)) {
                    throw new StorageNotFoundException(key);
                }
                throw new StorageException("READ_FAILED: head " + key, e);
            }
        }
        try {
            var resp = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return new StorageStat(resp.contentLength(), resp.contentType());
        } catch (NoSuchKeyException e) {
            throw new StorageNotFoundException(key);
        } catch (SdkException e) {
            throw new StorageException("READ_FAILED: head " + key, e);
        }
    }

    @Override
    public void put(String key, InputStream in, long size, String contentType) {
        requireRelativeKey(key);
        try {
            PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key);
            if (contentType != null) {
                req.contentType(contentType);
            }
            s3Client.putObject(req.build(), RequestBody.fromInputStream(in, size));
        } catch (SdkException e) {
            throw new StorageException("UPLOAD_FAILED: " + key, e);
        }
    }

    @Override
    public void put(String key, Path localFile, String contentType) {
        requireRelativeKey(key);
        try {
            PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key);
            if (contentType != null) {
                req.contentType(contentType);
            }
            // s3-transfer-manager 2.27.x 的 UploadRequest.Builder 无 source(Path) 重载，
            // 大文件分片上传经 AsyncRequestBody.fromFile（传输管理器自动分片 + 重试）
            Upload upload = transferManager.upload(
                    UploadRequest.builder()
                            .putObjectRequest(req.build())
                            .requestBody(AsyncRequestBody.fromFile(localFile))
                            .build());
            upload.completionFuture().join();
        } catch (CompletionException e) {
            // join() 将异步传输失败包装为 CompletionException → 解包真实原因后
            // 包装为存储异常（Review W2：原实现会以裸 CompletionException 500）
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new StorageException("UPLOAD_FAILED: " + key, cause);
        } catch (SdkException e) {
            throw new StorageException("UPLOAD_FAILED: " + key, e);
        }
    }

    @Override
    public void download(String key, Path destFile) {
        requireRelativeKey(key);
        try {
            s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), destFile);
        } catch (NoSuchKeyException e) {
            throw new StorageNotFoundException(key);
        } catch (SdkException e) {
            throw new StorageException("READ_FAILED: " + key, e);
        }
    }

    @Override
    public InputStream open(String key) {
        requireRelativeKey(key);
        if (isBaiduBce()) {
            try {
                return bosClient.getObject(bucket, key).getObjectContent();
            } catch (BceClientException e) {
                if (isNotFound(e)) {
                    throw new StorageNotFoundException(key);
                }
                throw new StorageException("READ_FAILED: " + key, e);
            }
        }
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new StorageNotFoundException(key);
        } catch (SdkException e) {
            throw new StorageException("READ_FAILED: " + key, e);
        }
    }

    @Override
    public InputStream open(String key, long start, long end) {
        requireRelativeKey(key);
        if (isBaiduBce()) {
            try {
                com.baidubce.services.bos.model.GetObjectRequest req =
                        new com.baidubce.services.bos.model.GetObjectRequest(bucket, key)
                                .withRange(start, end);
                return bosClient.getObject(req).getObjectContent();
            } catch (BceClientException e) {
                if (isNotFound(e)) {
                    throw new StorageNotFoundException(key);
                }
                throw new StorageException("READ_FAILED: " + key, e);
            }
        }
        try {
            return s3Client.getObject(software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .range("bytes=" + start + "-" + end)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new StorageNotFoundException(key);
        } catch (SdkException e) {
            throw new StorageException("READ_FAILED: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        requireRelativeKey(key);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            throw new StorageException("DELETE_FAILED: " + key, e);
        }
    }

    /**
     * 删除前缀匹配的全部 object key（对象存储无目录概念，前缀删除即完整清除）。
     * 与 local 模式 deleteByPrefix（删除目录内容并连带删除目录本身）语义等价。
     */
    @Override
    public void deleteByPrefix(String prefix) {
        requireRelativeKey(prefix);
        for (String key : list(prefix)) {
            delete(key);
        }
    }

    @Override
    public List<String> list(String prefix) {
        requireRelativeKey(prefix);
        List<String> keys = new ArrayList<>();
        try {

            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                    .overrideConfiguration(config ->
                            config.signer(Aws4Signer.create())
                    )
                    .bucket(bucket)
                    .prefix(prefix);
            try {
                ListObjectsV2Iterable pages = s3Client.listObjectsV2Paginator(req.build());
                for (ListObjectsV2Response page : pages) {
                    page.contents().forEach(o -> {
                        // 排除目录占位对象（key 以 "/" 结尾）——仅返回真正的文件 key，
                        // 与 LocalStorageService.list（Files::isRegularFile）语义保持一致。
                        if (!o.key().endsWith("/")) {
                            keys.add(o.key());
                        }
                    });
                }
            } catch (Exception e) {
                ListObjectsResponse response =
                        bosClient.listObjects(bucket, prefix);

                for (BosObjectSummary object : response.getContents()) {
                    // BOS listObjects 会把目录占位对象（如 tasks/）一并返回，其本身无数据，
                    // 打开会得到 null InputStream 导致读取 NPE，这里同样跳过。
                    if (object.getKey().endsWith("/")) {
                        continue;
                    }
                    log.debug("文件：" + object.getKey());
                    keys.add(object.getKey());
                }
            }
        } catch (SdkException e) {
            throw new StorageException("READ_FAILED: list prefix " + prefix, e);
        }
        return keys;
    }
}
