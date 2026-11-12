package com.isim.dims.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import java.net.URI;

/**
 * 百度 BOS 对象存储客户端配置
 *
 * <p>百度 BOS 原生 SDK 不兼容 JDK 17，故通过 AWS S3 SDK V2 的
 * S3 兼容协议访问 BOS（端点为 {@code https://s3.bj.bcebos.com}）。</p>
 *
 * <p>从 application.yml 读取 bos.* 配置项：
 * <ul>
 *   <li>endpoint — S3 兼容端点</li>
 *   <li>access-key / secret-key — BOS AK/SK（由用户填写）</li>
 *   <li>region — 区域（如 bj）</li>
 *   <li>bucket — 桶名（由用户填写）</li>
 * </ul>
 * </p>
 * @feature: infra
 */
@Configuration
@ConditionalOnProperty(name = "dims.storage.type", havingValue = "bos")
public class BosConfig {

    @Value("${bos.endpoint}")
    private String endpoint;

    @Value("${bos.access-key}")
    private String accessKey;

    @Value("${bos.secret-key}")
    private String secretKey;

    @Value("${bos.region}")
    private String region;

    /**
     * BOS S3 兼容客户端。
     * @Primary：MinioConfig 注册 minioS3Client 后容器中存在两个 S3Client Bean，
     * BosStorageService 按类型注入需解析到 BOS 客户端（MinIO 客户端经 @Qualifier 注入）。
     */
    @Bean
    @Primary
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(false)
                .build();
    }

    /**
     * S3 异步客户端：TransferManager 基于异步客户端实现（2.21+ 的
     * {@code S3TransferManager.Builder#s3Client} 仅接受 S3AsyncClient）。
     */
    @Bean
    public S3AsyncClient s3AsyncClient() {
        return S3AsyncClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(false)
                .build();
    }

    /**
     * @Primary：与 minioS3TransferManager 并存时，BosStorageService 按类型注入解析到 BOS
     */
    @Bean
    @Primary
    public S3TransferManager s3TransferManager(S3AsyncClient s3AsyncClient) {
        return S3TransferManager.builder()
                .s3Client(s3AsyncClient)
                .build();
    }
}
