package com.isim.dims.service.impl;

import com.isim.dims.service.CfdiConversionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.isim.dims.util.CfdiConverterClient;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.isim.dims.util.CfdiConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CFDI 数据包转换 Service 实现
 * <p>
 * 处理 tar.gz → 解压 → Docker 转换 → bag/h265 产物归位 → 清理的完整流程。
 * 工作目录结构（StorageAccess#cfdiWorkDir：local → cfdiDir/{sliceId}，minio → tempDir/cfdi/{sliceId}）：
 * {workDir}/
 * public/           ← 解压后的原始文件
 * meta/             ← 元数据目录（由 extractTarGz 解压）
 * mapping_input.tar.gz  ← repackForDocker 产物
 * mapping_input.bag     ← Docker 转换产物
 *
 * <p>MinIO 迁移：输入输出全部经 {@link StorageAccess} 门面——输入 tar 先物化到工作区，
 * 产物（bag/h265）写回存储（local → 本地目录，minio → 对象存储）。</p>
 *
 * @feature: cfdi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CfdiConversionServiceImpl implements CfdiConversionService {

    private final CfdiConverterClient cfdiConverterClient;
    private final StorageAccess storageAccess;

    /**
     * h265 文件名前缀 → sensor_name 映射表
     * 引用 CfdiConstants 共享常量
     */
    private static final Map<String, String> H265_SENSOR_MAP = CfdiConstants.H265_SENSOR_MAP;

    // ========================================================================
    // 1. 解压 tar.gz
    // ========================================================================

    @Override
    public Path extractTarGz(String sliceId, String tarRef) {
        Path workDir = storageAccess.cfdiWorkDir(sliceId);
        try {
            Files.createDirectories(workDir);

            // 物化 tar 到本地（local 零拷贝解析为本地路径；minio 下载到缓存，TTL 清理）
            Path tarFile = storageAccess.materializeLocal(tarRef);

            // 执行 tar xzf 解压到 workDir
            ProcessBuilder pb = new ProcessBuilder(
                    "tar", "xzf", tarFile.toAbsolutePath().toString(),
                    "-C", workDir.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);

            log.info("Extracting tar.gz: {} → {}", tarFile, workDir);
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String errorOutput = new String(process.getInputStream().readAllBytes());
                log.error("Failed to extract tar.gz: {} (exit code {}): {}", tarFile, exitCode, errorOutput);
                throw new RuntimeException(
                        "Failed to extract tar.gz, exit code " + exitCode + ": " + errorOutput);
            }

            // 期望解压后有 public/ 子目录
            Path publicDir = workDir.resolve("public");
            if (!Files.isDirectory(publicDir)) {
                log.error("Extracted tar.gz does not contain public/ directory: {}", tarFile);
                throw new RuntimeException(
                        "Extracted tar.gz does not contain public/ directory: " + tarFile);
            }

            log.info("Extraction complete: {} ({} -> {})", workDir, tarFile.getFileName(), publicDir);
            return publicDir;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Extraction interrupted for sliceId={}", sliceId, e);
            throw new RuntimeException("Extraction interrupted for sliceId=" + sliceId, e);
        } catch (IOException e) {
            log.error("Failed to extract tar.gz for sliceId={}", sliceId, e);
            throw new RuntimeException("Failed to extract tar.gz for sliceId=" + sliceId, e);
        }
    }

    // ========================================================================
    // 2. 打包 mapping_input.tar.gz
    // ========================================================================

    @Override
    public Path repackForDocker(Path publicDir) {
        Path workDir = publicDir.getParent();
        Path outputTar = workDir.resolve("mapping_input.tar.gz");

        try {
            // tar czf mapping_input.tar.gz -C public/ .
            ProcessBuilder pb = new ProcessBuilder(
                    "tar", "czf", outputTar.toAbsolutePath().toString(),
                    "-C", publicDir.toAbsolutePath().toString(), "."
            );
            pb.redirectErrorStream(true);

            log.info("Repacking public/ → {}", outputTar.getFileName());
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String errorOutput = new String(process.getInputStream().readAllBytes());
                throw new RuntimeException(
                        "Failed to repack public/ as tar.gz, exit code " + exitCode + ": " + errorOutput);
            }

            log.info("Repack complete: {} ({} bytes)", outputTar, Files.size(outputTar));
            return outputTar;

        } catch (InterruptedException e) {
            log.error("Repack interrupted for {}", publicDir, e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Repack interrupted for " + publicDir, e);
        } catch (IOException e) {
            log.error("Failed to repack tar.gz for {}", publicDir, e);
            throw new RuntimeException("Failed to repack tar.gz for " + publicDir, e);
        }
    }

    // ========================================================================
    // 3. Docker 转换
    // ========================================================================

    @Override
    @SuppressWarnings("unchecked")
    public Path runConversion(Path tarFile, Path workDir) {
        // LUS_app.py 期望的 JSON 格式：
        // {"version":"test","metadata":{},"output":"/data",
        //  "data":[{"md5":"","path":"/data/mapping_input.tar.gz","metadata":{"name":"mapping_input.tar.gz"}}]}
        String fileName = tarFile.getFileName().toString();
        Map<String, Object> dataItemMeta = new HashMap<>();
        dataItemMeta.put("name", fileName);

        Map<String, Object> dataItem = new HashMap<>();
        dataItem.put("md5", "");
        // 两个容器共享同一个 /data，直接传递实际绝对路径，避免 CFDI 服务
        // 再将虚拟的 /data/<file> 路径重写到 work_dir 时产生歧义。
        dataItem.put("path", tarFile.toAbsolutePath().normalize().toString());
        dataItem.put("metadata", dataItemMeta);

        Map<String, Object> args = new HashMap<>();
        args.put("version", "test");
        args.put("metadata", new HashMap<>());
        args.put("output", "/data");
        args.put("data", Collections.singletonList(dataItem));

        log.debug("Invoking CFDI converter: workDir={}, args={}", workDir, args);
        JsonNode response = cfdiConverterClient.convert(workDir, args);
        log.debug("CFDI converter response: {}", response);
        if (response == null || !"SUCCEEDED".equals(response.path("status").asText())) {
            throw new RuntimeException("CFDI converter returned an unsuccessful response: " + response);
        }

        // 期望产物 mapping_input.bag 出现在 workDir 下
        Path bagFile = workDir.resolve("mapping_input.bag");
        if (!Files.exists(bagFile)) {
            throw new RuntimeException(
                    "CFDI conversion did not produce mapping_input.bag in " + workDir);
        }

        try {
            log.info("Docker conversion complete: {} ({} bytes)", bagFile, Files.size(bagFile));
        } catch (IOException e) {
            log.warn("Failed to get size of converted bag file: {}", bagFile, e);
        }
        return bagFile;
    }

    // ========================================================================
    // 4. 复制 bag 到持久存储
    // ========================================================================

    @Override
    public String copyBagToStorage(Path bagPath, String sliceId) {
        // 统一写回存储引用 slices/{id}/original.bag：
        // local 模式 put(Path) = Files.move 到 bagDir/{id}/original.bag（旧行为等价），
        // minio 模式经 S3TransferManager 分片上传
        String key = StorageKeys.originalBag(sliceId);
        storageAccess.put(key, bagPath, "application/octet-stream");
        log.info("Bag written to storage: {} → {}", bagPath, key);
        return key;
    }

    // ========================================================================
    // 5. 复制 h265 视频文件
    // ========================================================================

    @Override
    public void copyH265Videos(Path publicDir, String sliceId) {
        // h265 文件位于 public/generated/topics/images/
        Path imagesDir = publicDir.resolve("generated").resolve("topics").resolve("images");
        if (!Files.isDirectory(imagesDir)) {
            log.warn("H265 images directory not found: {}, skipping video copy", imagesDir);
            return;
        }

        int copiedCount = 0;
        List<Path> h265Files;
        try (Stream<Path> walk = Files.walk(imagesDir)) {
            h265Files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".h265"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Failed to walk h265 directory {} for sliceId={}", imagesDir, sliceId, e);
            return;
        }

        for (Path h265File : h265Files) {
            String fileName = h265File.getFileName().toString();
            String prefix = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;

            if (H265_SENSOR_MAP.containsKey(prefix)) {
                // 写回存储引用 videos/{id}/{file}（local → videoDir/{id}；minio → 对象存储）。
                // contentType 传 null：local 忽略，minio 存 null，VideoStreamingController
                // 读取时回退 resolveContentType(filename)（.h265 无标准 MIME）
                String key = StorageKeys.video(sliceId, fileName);
                storageAccess.put(key, h265File, null);
                copiedCount++;
                log.debug("Copied h265: {} → {}", fileName, key);
            } else {
                log.debug("Skipping unknown h265 file (no sensor mapping): {}", fileName);
            }
        }

        log.info("H265 copy complete: {} files written to storage for sliceId={}", copiedCount, sliceId);
    }

    // ========================================================================
    // 6. 清理临时目录
    // ========================================================================

    @Override
    public void cleanupTempDir(String sliceId) {
        Path workDir = storageAccess.cfdiWorkDir(sliceId);
        if (!Files.exists(workDir)) {
            log.debug("Temp dir already cleaned or not exists: {}", workDir);
            return;
        }

        try (Stream<Path> walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete temp file: {}", path, e);
                        }
                    });
            log.info("Cleaned temp dir: {}", workDir);
        } catch (IOException e) {
            log.warn("Failed to clean temp dir {} for sliceId={}", workDir, sliceId, e);
        }
    }
}
