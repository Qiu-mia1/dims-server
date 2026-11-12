package com.isim.dims.integration;

import com.isim.dims.exception.StorageNotFoundException;
import com.isim.dims.service.StorageStat;
import com.isim.dims.service.impl.MinioStorageService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MinIO 真实连通性冒烟测试（MinIO 迁移）
 *
 * <p>对运行中的 MinIO（本机 9000 ads-minio 容器）执行全链路：
 * put(InputStream) → stat → exists → open(range) 服务端裁剪 → delete →
 * put(Path)（TransferManager 分片上传分支）→ deleteByPrefix → list 验证清空。</p>
 *
 * <p>凭证零入库：全部经环境变量 MINIO_ENDPOINT / MINIO_ACCESS_KEY /
 * MINIO_SECRET_KEY / MINIO_BUCKET 注入（与 MinioConfig 生产装配一致），
 * 四变量缺一即整类跳过（@EnabledIfEnvironmentVariable 在上下文启动前求值）。</p>
 *
 * <p>运行方式（本机共享 MinIO：ads-minio 容器，凭证见
 * /home/nnn/project/recall/docker/docker-compose.yml）：</p>
 * <pre>
 * MINIO_ENDPOINT=http://localhost:9000 \
 * MINIO_ACCESS_KEY=minioadmin \
 * MINIO_SECRET_KEY=minioadmin \
 * MINIO_BUCKET=dims-smoke \
 * mvn -o test -Dtest='MinioStorageServiceIntegrationTest'
 * </pre>
 *
 * <p>使用唯一前缀 dims-smoke/{uuid}/ 隔离每次运行，@AfterAll 清理，不污染共享桶。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "dims.storage.type=minio"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MINIO_ENDPOINT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MINIO_ACCESS_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MINIO_SECRET_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MINIO_BUCKET", matches = ".+")
class MinioStorageServiceIntegrationTest {

    /** 每次运行唯一前缀，隔离并便于清理 */
    private static final String PREFIX = "dims-smoke/" + UUID.randomUUID() + "/";

    /** 恰好 100 字节，方便 range 裁剪断言 */
    private static final byte[] PAYLOAD = "0123456789".repeat(10).getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MinioStorageService storageService;

    @Autowired
    @Qualifier("minioS3Client")
    private S3Client s3Client;

    private String bucket;

    @BeforeAll
    void ensureBucket() {
        bucket = System.getenv("MINIO_BUCKET");
        // 冒烟测试自建专用桶（不存在时创建），避免依赖共享桶预配置
        boolean exists;
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            exists = true;
        } catch (NoSuchBucketException e) {
            exists = false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                exists = false;
            } else {
                throw e;
            }
        }
        if (!exists) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @AfterAll
    void cleanup() {
        s3Client.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(PREFIX))
                .forEach(page -> page.contents().forEach(
                        o -> s3Client.deleteObject(b -> b.bucket(bucket).key(o.key()))));
    }

    @Test
    void fullChain_putStatExistsRangeDelete() throws IOException {
        String key = PREFIX + "slice-a/original.bag";

        storageService.put(key, new ByteArrayInputStream(PAYLOAD), PAYLOAD.length, "application/octet-stream");

        // stat：大小 + 内容类型
        StorageStat stat = storageService.stat(key);
        assertThat(stat.size()).isEqualTo(PAYLOAD.length);
        assertThat(stat.contentType()).isEqualTo("application/octet-stream");

        // exists
        assertThat(storageService.exists(key)).isTrue();

        // 服务端 range 裁剪：0-49
        byte[] head;
        try (InputStream in = storageService.open(key, 0, 49)) {
            head = in.readAllBytes();
        }
        assertThat(head).isEqualTo(Arrays.copyOfRange(PAYLOAD, 0, 50));

        // 服务端 range 裁剪：50-99
        byte[] tail;
        try (InputStream in = storageService.open(key, 50, 99)) {
            tail = in.readAllBytes();
        }
        assertThat(tail).isEqualTo(Arrays.copyOfRange(PAYLOAD, 50, 100));

        // 全量读
        byte[] full;
        try (InputStream in = storageService.open(key)) {
            full = in.readAllBytes();
        }
        assertThat(full).isEqualTo(PAYLOAD);

        // delete 后不可见
        storageService.delete(key);
        assertThat(storageService.exists(key)).isFalse();
        assertThatThrownBy(() -> storageService.stat(key))
                .isInstanceOf(StorageNotFoundException.class);
    }

    @Test
    void putByPathAndDeleteByPrefix() throws IOException {
        // put(Path)：TransferManager 分片上传分支
        Path tmp = Files.createTempFile("dims-smoke-", ".bag");
        Files.write(tmp, PAYLOAD);
        try {
            storageService.put(PREFIX + "slice-b/original.bag", tmp, "application/octet-stream");
        } finally {
            Files.deleteIfExists(tmp);
        }
        String secondKey = PREFIX + "slice-b/notes.json";
        storageService.put(secondKey,
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 2, "application/json");

        assertThat(storageService.list(PREFIX + "slice-b/")).containsExactlyInAnyOrder(
                PREFIX + "slice-b/original.bag", secondKey);

        // deleteByPrefix 全清
        storageService.deleteByPrefix(PREFIX + "slice-b/");
        assertThat(storageService.list(PREFIX + "slice-b/")).isEmpty();
    }

    @Test
    void invalidKey_Rejected() {
        assertThatThrownBy(() -> storageService.put("/absolute/key", new ByteArrayInputStream(new byte[0]), 0, null))
                .hasMessageContaining("STORAGE_INVALID_KEY");
        assertThatThrownBy(() -> storageService.open("../etc/passwd"))
                .hasMessageContaining("STORAGE_INVALID_KEY");
        assertThatThrownBy(() -> storageService.list("a/../b"))
                .hasMessageContaining("STORAGE_INVALID_KEY");
    }

    @Test
    void list_ReturnsKeysUnderPrefix() {
        storageService.put(PREFIX + "list/a.txt",
                new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");
        storageService.put(PREFIX + "list/b.txt",
                new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");
        storageService.put(PREFIX + "other/c.txt",
                new ByteArrayInputStream("c".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");

        List<String> keys = storageService.list(PREFIX + "list/");
        assertThat(keys).containsExactlyInAnyOrder(PREFIX + "list/a.txt", PREFIX + "list/b.txt");
    }
}
