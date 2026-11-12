package com.isim.dims.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.config.SigLIPConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * SigLIP2 embedding 服务客户端（以图搜图 / 以文搜图 / 特征搜图）
 *
 * <p>对接 dims-python/siglip_service.py（FastAPI）：
 * <ul>
 *   <li>POST {url}/embedding        multipart 文件 → { shape, embedding } 图像特征</li>
 *   <li>POST {url}/text-embedding   JSON {text}   → { shape, embedding } 文本特征</li>
 * </ul>
 * 未启用（dims.siglip.enabled=false）时 {@link #isEnabled()} 返回 false，
 * 调用方应据此返回 FEATURES_DISABLED 而非发起请求。</p>
 *
 * @feature: visualization
 */
@Slf4j
@Component
public class SigLIPClient {

    private static final String BOUNDARY = "----isim-dims-" + UUID.randomUUID();

    private final SigLIPConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http;

    public SigLIPClient(SigLIPConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public int embedDims() {
        return config.getEmbedDims();
    }

    /**
     * 计算单张图片的 embedding（POST multipart 文件）
     *
     * @param imageBytes JPEG/PNG 图片字节
     * @return embedding 向量；失败时抛 {@link RuntimeException}
     */
    public double[] embedImage(byte[] imageBytes) {
        byte[] body = multipartBody(imageBytes);
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(config.getUrl() + "/embedding"))
                            .timeout(Duration.ofSeconds(config.getTimeoutSec()))
                            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("SigLIP embedding failed: HTTP " + resp.statusCode() + " " + truncate(resp.body()));
            }
            return parseEmbedding(resp.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SigLIP embedding request failed: " + e.getMessage(), e);
        }
    }

    /**
     * 计算文本 prompt 的 embedding（POST JSON {text}）
     *
     * @param text 检索文本，如 “出现红绿灯的路口”
     * @return embedding 向量
     */
    public double[] embedText(String text) {
        try {
            String url = config.getUrl() + "/text-embedding";

            String body = objectMapper.writeValueAsString(
                    Map.of("text", text == null ? "" : text)
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getTimeoutSec()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body,
                            StandardCharsets.UTF_8
                    ))
                    .build();

            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                        "SigLIP text embedding failed: HTTP "
                                + response.statusCode()
                                + " "
                                + truncate(response.body())
                );
            }

            return parseEmbedding(response.body());

        } catch (IOException e) {
            throw new RuntimeException(
                    "SigLIP text embedding request failed: " + e.getMessage(), e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "SigLIP text embedding request interrupted", e
            );
        }
    }

    private double[] parseEmbedding(String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.readValue(body, Map.class);
        Object emb = map.get("embedding");
        if (!(emb instanceof java.util.List<?> list)) {
            throw new RuntimeException("SigLIP response missing 'embedding'");
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = ((Number) list.get(i)).doubleValue();
        }
        return out;
    }

    /**
     * 构造 multipart/form-data 请求体（字段名 file）
     */
    private byte[] multipartBody(byte[] imageBytes) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] fileName = ("frame_" + UUID.randomUUID() + ".jpg").getBytes(StandardCharsets.UTF_8);
            write(bos, ("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
            write(bos, ("Content-Disposition: form-data; name=\"file\"; filename=\"")
                    .getBytes(StandardCharsets.UTF_8));
            write(bos, fileName);
            write(bos, ("\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            write(bos, imageBytes);
            write(bos, ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build multipart body", e);
        }
    }

    private static void write(ByteArrayOutputStream bos, byte[] bytes) throws IOException {
        bos.write(bytes);
    }

    private static String truncate(String s) {
        return s != null && s.length() > 300 ? s.substring(0, 300) : s;
    }
}