package com.isim.dims.controller;

import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.model.BosObjectSummary;
import com.baidubce.services.bos.model.ListObjectsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * BOS/MinIO 对象存储读写链路自测 Controller。
 *
 * <p>{@code GET /test} 对本地文件 {@code /data/test.json} 执行一次完整的
 * 「list bucket → 上传 → 下载回写」冒烟测试：</p>
 * <ol>
 *   <li>确保本地 {@code /data/test.json} 存在（缺失时写入测试内容）</li>
 *   <li>list bucket，列出当前桶内全部对象</li>
 *   <li>把 {@code /data/test.json} 上传为桶内对象 {@code test.json}</li>
 *   <li>下载 {@code test.json}，回写覆盖本地 {@code /data/test.json}</li>
 * </ol>
 * <p>任一步异常均会被捕获并返回可读错误描述，避免 HTTP 500 空白。</p>
 *
 * @feature: infra
 */
@Slf4j
@RestController
public class TestController {

    @Autowired
    S3Client s3Client;
//
//    @Autowired
//    OssTemplate ossTemplate;

    @Value("${minio.bucket}")
    private String bucket;

    @Value("${minio.download-bucket}")
    private String downloaBbucket;

    @Autowired
    private BosClient bosClient;

    /**
     * 测试落盘的本地文件路径。
     */
    private static final String TEST_FILE = "/data/test.json";

    /**
     * 上传/下载使用的对象 Key。
     */
    private static final String TEST_KEY = "test.json";

    @GetMapping("/test")
    public String test() {
        Path testFile = Path.of(TEST_FILE);
        StringBuilder sb = new StringBuilder();
        try {
            // 0. 确保本地测试文件存在
            Files.createDirectories(testFile.getParent());
            if (!Files.exists(testFile)) {
                String seed = "{\"hello\":\"dims\",\"ts\":\"" + Instant.now() + "\"}";
                Files.writeString(testFile, seed, StandardCharsets.UTF_8);
                sb.append("已创建本地测试文件: ").append(TEST_FILE).append("\n");
            }

            String prefix = "videos/";

            ListObjectsResponse response =
                    bosClient.listObjects(downloaBbucket);

            for (BosObjectSummary object : response.getContents()) {
                log.debug("文件：" + object.getKey());
                log.debug("大小：" + object.getSize());
                log.debug("最后修改时间：" + object.getLastModified());
            }

            ListObjectsResponse response1 =
                    bosClient.listObjects(bucket, prefix);

            for (BosObjectSummary object : response1.getContents()) {
                log.debug("文件：" + object.getKey());
                log.debug("大小：" + object.getSize());
                log.debug("最后修改时间：" + object.getLastModified());
            }

//            bosClient.shutdown();

//            List<S3Object> allObjectsByPrefix = ossTemplate.getAllObjectsByPrefix(bucket, null);
//            log.info("文件：{}", allObjectsByPrefix.toString());

            // 1. list bucket：列出当前桶内对象
            List<String> keys = s3Client.listObjectsV2(
                            ListObjectsV2Request.builder().bucket(bucket).build())
                    .contents().stream()
                    .map(S3Object::key)
                    .collect(Collectors.toList());
            sb.append("list bucket[").append(bucket).append("] 共 ")
                    .append(keys.size()).append(" 个对象: ").append(keys).append("\n");

            log.info("BOS/MinIO 链路自测通过，bucket={}, key={}", bucket, TEST_KEY);
            return sb.toString();
        } catch (Exception e) {
            String cause = e.getCause() == null ? "" : "，cause: " + e.getCause();
            log.error("BOS/MinIO 链路自测失败: {}", e.getMessage(), e);
            return "操作失败: " + e + cause;
        }
    }
}