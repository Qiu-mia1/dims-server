package com.isim.dims.config;

import com.baidubce.Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import com.baidubce.auth.DefaultBceCredentials;
import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.BosClientConfiguration;

import java.net.URI;

/**
 * MinIO 对象存储客户端配置（MinIO 迁移）
 *
 * <p>仅当 {@code dims.storage.type=minio} 时装配。密钥零入库：全部经环境变量注入
 * （MINIO_ENDPOINT / MINIO_ACCESS_KEY / MINIO_SECRET_KEY / MINIO_BUCKET），
 * application.yml 中不落任何明文。启动时四项非空校验 fail-fast。</p>
 *
 * <p><b>Bean 命名</b>：与 BosConfig 已注册的 s3Client/s3AsyncClient/s3TransferManager
 * 区分，统一使用 minioS3Client / minioS3AsyncClient / minioS3TransferManager，
 * 消费方（MinioStorageService）按名注入；BosConfig 的 s3Client 标记 @Primary
 * 保持 BosStorageService 按类型注入解析到 BOS 客户端。</p>
 *
 * <p>MinIO 必须 forcePathStyle(true)（虚拟主机风格桶名不适用）。</p>
 *
 * @feature: infra
 */
@Configuration
@ConditionalOnProperty(name = "dims.storage.type", havingValue = "minio")
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Value("${minio.pathstyleAccess: false}")
    private Boolean pathstyleAccess;

    @Value("${minio.bucket}")
    private String bucket;

    @Value("${minio.region:us-east-1}")
    private String region;

    @Value("${bos.access-key}")
    private String bosAccessKey;

    @Value("${bos.secret-key}")
    private String bosSecretKey;


    /**
     * 显式直连（禁用代理）：本机环境变量 HTTP(S)_PROXY 指向 127.0.0.1:7892，
     * AWS SDK 2.x 默认读取环境/系统属性代理设置，会把 localhost 的 MinIO
     * 请求误路由到代理导致 502。显式关闭两类代理来源即直连（见 2026-08-13
     * MinIO 迁移踩坑记录）。注意 2.27.x 中 ProxyConfiguration 已下沉到各
     * HTTP 客户端模块（http.apache / http.nio.netty），不再是 http-client-spi
     * 的公共类，故此处按客户端各自定义。
     */
    private static final software.amazon.awssdk.http.apache.ProxyConfiguration NO_PROXY =
            software.amazon.awssdk.http.apache.ProxyConfiguration.builder()
                    .useEnvironmentVariableValues(false)
                    .useSystemPropertyValues(false)
                    .build();

    private static final software.amazon.awssdk.http.nio.netty.ProxyConfiguration NO_PROXY_NETTY =
            software.amazon.awssdk.http.nio.netty.ProxyConfiguration.builder()
                    .useEnvironmentVariableValues(false)
                    .useSystemPropertyValues(false)
                    .build();

    private void failFast() {
        if (isBlank(endpoint) || isBlank(accessKey) || isBlank(secretKey) || isBlank(bucket)) {
            throw new IllegalStateException(
                    "dims.storage.type=minio requires minio.endpoint / minio.access-key / minio.secret-key / minio.bucket "
                            + "to be set via environment variables MINIO_ENDPOINT / MINIO_ACCESS_KEY / MINIO_SECRET_KEY / MINIO_BUCKET");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Bean
    public S3Client minioS3Client() {
        failFast();

        return S3Client.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)
                        )
                )
                .serviceConfiguration(
                        S3Configuration.builder()
                                .build()
                )
                .forcePathStyle(pathstyleAccess)
                .httpClientBuilder(
                        ApacheHttpClient.builder()
                                .proxyConfiguration(NO_PROXY)
                )
                .build();
    }


    @Bean
    public S3AsyncClient minioS3AsyncClient() {
        failFast();
        return S3AsyncClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder().proxyConfiguration(NO_PROXY_NETTY))
                .build();
    }

    @Bean
    public S3TransferManager minioS3TransferManager(S3AsyncClient minioS3AsyncClient) {
        return S3TransferManager.builder()
                .s3Client(minioS3AsyncClient)
                .build();
    }

    @Bean
    public BosClient createClient() {
        BosClientConfiguration config = new BosClientConfiguration();

        config.setCredentials(
                new DefaultBceCredentials(bosAccessKey, bosSecretKey)
        );

        // 根据你的 Bucket 所在 Region 配置
        config.setEndpoint("su.bcebos.com");
        config.setProtocol(Protocol.HTTPS);

        return new BosClient(config);
    }
}
