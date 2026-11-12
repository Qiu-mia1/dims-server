package com.isim.dims.service.impl;

import com.isim.dims.service.CfdiConversionService;
import com.isim.dims.service.FisheyePreviewService;
import com.isim.dims.util.PythonExecutor;
import com.isim.dims.util.StorageKeys;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 前视鱼眼预览视频实现
 *
 * <p>预览生成复用 CFDI 导入链路（与 {@code LocalDirectoryImportPipelineServiceImpl#processSession}
 * 步骤 2~3 一致）：</p>
 * <ol>
 *   <li>复制 {sessionDir}/public → 预览工作区 public/（源目录只读）；</li>
 *   <li>{@link CfdiConversionService#repackForDocker} 打包 mapping_input.tar.gz；</li>
 *   <li>{@link CfdiConversionService#runConversion} 调用 CFDI 转换器把 mfbag 转成 mapping_input.bag；</li>
 *   <li>调 Python {@code extract_video_segment.py} 从 bag 的前视鱼眼 topic 抽帧并 mux 成分片
 *       MP4，写入 videos/{sliceId}/preview_front_fisheye.mp4。</li>
 * </ol>
 *
 * <p>转换不可用（如 meta-only 会话尚无 mfbag）时回退：直接对会话目录里的前视鱼眼 h265
 * 用 ffmpeg 重编码为 H.264 mp4（历史行为）。</p>
 *
 * <p>转码失败不影响切片入库（META_READY 依旧可见，仅预览不可播）。</p>
 *
 * @feature: slice
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FisheyePreviewServiceImpl implements FisheyePreviewService {

    /**
     * 预览工作区后缀：与完整导入流水线共用 cfdiWorkDir 命名空间但目录隔离，
     * 避免预览转换与 {sliceId} 导入流水线互相覆盖/清理
     */
    private static final String WORK_DIR_SUFFIX = "-preview";

    /**
     * bag → 预览 mp4 的 Python 脚本（与可视化视频提取同源）
     */
    private static final String EXTRACT_SCRIPT = "extract_video_segment.py";

    private final StorageAccess storageAccess;
    private final CfdiConversionService cfdiConversionService;
    private final PythonExecutor pythonExecutor;

    /**
     * 预览首选 topic：前视广角 F100_front_wide（与 camera_event_config.json 中一致）。
     * 数据包含 11 路视频流时用它生成预览。
     */
    @Value("${dims.preview.wide-topic:/sensor/camera_front_wide/video}")
    private String wideTopic;

    /**
     * 降级 topic：数据包中无广角视频流时的前视鱼眼 F195_front_fisheye。
     */
    @Value("${dims.preview.fisheye-topic:/sensor/camera_front_fisheye/video}")
    private String fisheyeTopic;

    /**
     * bag 抽帧 + mux 超时（秒）
     */
    @Value("${dims.preview.bag-extract-timeout-sec:900}")
    private int bagExtractTimeoutSec;

    /**
     * ffmpeg 可执行文件路径（部署环境需安装）
     */
    @Value("${dims.preview.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    /**
     * 单个预览转码超时（分钟，仅 h265 回退路径使用）
     */
    @Value("${dims.preview.ffmpeg-timeout-minutes:30}")
    private long ffmpegTimeoutMinutes;

    /**
     * 预览转码并发数（避免一次 meta-only 导入多个会话时挤爆 CPU）
     */
    @Value("${dims.preview.concurrency:2}")
    private int concurrency;

    private ExecutorService executor;

    private synchronized ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
                Thread t = new Thread(r, "fisheye-preview");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void generatePreviewAsync(String sliceId, String sessionDirPath) {
        executor().submit(() -> {
            try {
                generatePreview(sliceId, sessionDirPath);
            } catch (Exception e) {
                log.warn("fisheye preview generation failed for slice {}: {}", sliceId, e.getMessage(), e);
            }
        });
    }

    @Override
    public void generatePreview(String sliceId, String sessionDirPath) {
        Path workDir = null;
        try {
            // 预览工作区与导入流水线 {sliceId} 工作区隔离（两者都会产生 public/ 与 bag）
            workDir = storageAccess.cfdiWorkDir(sliceId + WORK_DIR_SUFFIX);
            boolean done = false;
            Path bag = convertSessionToBag(sliceId, sessionDirPath, workDir);
            if (bag != null) {
                try {
                    publishPreview(sliceId, extractPreviewFromBag(sliceId, bag));
                    done = true;
                } catch (Exception e) {
                    // bag 中没有候选题 topic（包内既无广角也无鱼眼）/ 抽帧失败：仍尝试会话目录里的 h265
                    log.warn("bag → preview extraction failed for slice {} ({}), falling back to raw h265",
                            sliceId, e.getMessage());
                }
            }
            if (!done) {
                // 无 mfbag、转换不可用或抽帧失败：回退到前视 h265（广角优先、鱼眼兜底）直接 ffmpeg 转码
                generateFromH265(sliceId, sessionDirPath);
            }
        } catch (Exception e) {
            log.warn("fisheye preview generation failed for slice {}: {}", sliceId, e.getMessage(), e);
        } finally {
            cleanupQuietly(workDir);
        }
    }

    @Override
    public boolean hasPreview(String sliceId) {
        try {
            return storageAccess.exists(StorageKeys.video(sliceId, PREVIEW_VIDEO_NAME));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 复用 CFDI 转换链路把会话目录里的 mfbag 转成 bag：
     * 复制 public/ → repackForDocker → runConversion。
     *
     * @return bag 本地路径；转换不可用时返回 null（调用方回退到 h265 直转）
     */
    private Path convertSessionToBag(String sliceId, String sessionDirPath, Path workDir) {
        try {
            Path sourcePublic = resolvePublicSource(sessionDirPath);
            if (sourcePublic == null) {
                log.info("no session source dir under {}, skip preview for slice {}", sessionDirPath, sliceId);
                return null;
            }
            Path publicDirInWork = workDir.resolve("public");
            log.info("Fisheye preview step 1: copying {} → {}", sourcePublic, publicDirInWork);
            copyDirectory(sourcePublic, publicDirInWork);

            Path tarFile = cfdiConversionService.repackForDocker(publicDirInWork);
            log.info("Fisheye preview step 2: converting mfbag → bag for slice {}", sliceId);
            return cfdiConversionService.runConversion(tarFile, workDir);
        } catch (Exception e) {
            log.warn("mfbag → bag conversion unavailable for slice {} ({}), preview falls back to raw h265",
                    sliceId, e.getMessage());
            return null;
        }
    }

    /**
     * 转换源目录：与导入流水线一致优先 {sessionDir}/public，无 public/ 时以会话目录本身为源。
     */
    private Path resolvePublicSource(String sessionDirPath) {
        if (sessionDirPath == null || sessionDirPath.isBlank()) {
            return null;
        }
        Path sessionDir = Paths.get(sessionDirPath);
        if (!Files.isDirectory(sessionDir)) {
            return null;
        }
        Path publicDir = sessionDir.resolve("public");
        return Files.isDirectory(publicDir) ? publicDir : sessionDir;
    }

    /**
     * 从 bag 中抽取前视候选 topic 的视频（Python 抽帧 + ffmpeg mux 成分片 MP4）。
     *
     * <p>按优先级候选：首选广角 topic，数据包中无广角视频流（topic 不存在 / 抽帧无输出）
     * 时降级到前视鱼眼 topic。全部候选都没有输出才判定抽帧失败，
     * 由调用方回退到 h265 直转。</p>
     *
     * <p>输出位置：minio 模式先写 staging（随后上传对象存储），
     * local 模式由脚本直接写最终位置 videos/{sliceId}/preview_front_fisheye.mp4。</p>
     */
    private Path extractPreviewFromBag(String sliceId, Path bag) throws IOException {
        String key = StorageKeys.video(sliceId, PREVIEW_VIDEO_NAME);
        boolean minio = storageAccess.isMinioMode();
        Path output = minio
                ? storageAccess.stageDir("videos").resolve(sliceId).resolve(PREVIEW_VIDEO_NAME)
                : storageAccess.resolveLocalPath(key);
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<String> topics = previewTopics();
        Exception last = null;
        for (String topic : topics) {
            try {
                // Python 脚本在 topic 不存在时会非 0 退出（抛出 RuntimeException），
                // 这里统一兜住以便降级到下一个候选 topic
                Files.deleteIfExists(output);
                runTopicExtraction(sliceId, bag, output, topic);
                return output;
            } catch (Exception e) {
                last = e;
                log.info("preview topic {} not usable for slice {} ({}), trying next candidate",
                        topic, sliceId, e.getMessage());
            }
        }
        // 全部候选都不可用：抛给上层，由调用方回退到 h265 直转
        throw new IOException("no preview stream extracted from bag for slice " + sliceId
                + ", topics tried: " + topics, last);
    }

    /**
     * 预览候选 topic，按优先级排列：首选前视广角，降级前视鱼眼（去重、去空）。
     */
    private List<String> previewTopics() {
        List<String> topics = new ArrayList<>();
        for (String t : new String[]{wideTopic, fisheyeTopic}) {
            if (t != null && !t.isBlank() && !topics.contains(t)) {
                topics.add(t);
            }
        }
        return topics;
    }

    /**
     * 对单个 topic 执行 bag 抽帧，产出非空 mp4；否则抛 IOException 交由候选循环降级。
     */
    private void runTopicExtraction(String sliceId, Path bag, Path output, String topic) throws IOException {
        Map<String, Object> args = new HashMap<>();
        args.put("bag_path", bag.toAbsolutePath().toString());
        args.put("output_path", output.toAbsolutePath().toString());
        args.put("topic", topic);
        args.put("output_format", "mp4");
        log.info("Fisheye preview step 3: extracting {} from bag {} for slice {}", topic, bag, sliceId);
        pythonExecutor.execute(EXTRACT_SCRIPT, args, bagExtractTimeoutSec);

        if (!Files.exists(output) || Files.size(output) == 0) {
            throw new IOException("preview extraction produced no output: " + output);
        }
    }

    /**
     * 预览 mp4 归档：minio 模式上传对象存储；local 模式脚本已写最终位置，无需再 put。
     */
    private void publishPreview(String sliceId, Path mp4) throws IOException {
        if (storageAccess.isMinioMode()) {
            storageAccess.put(StorageKeys.video(sliceId, PREVIEW_VIDEO_NAME), mp4, "video/mp4");
        }
        log.info("fisheye preview generated for slice {}: {} ({} bytes)",
                sliceId, mp4, Files.size(mp4));
    }

    /**
     * 回退路径：会话目录里的前视 h265
     * （优先 generated/topics/images/F100_front_wide*.h265，无则 F195_front_fisheye*.h265）
     * 直接用 ffmpeg 重编码为 H.264 mp4。
     */
    private void generateFromH265(String sliceId, String sessionDirPath) throws Exception {
        Path h265 = findFisheyeFile(sessionDirPath);
        if (h265 == null) {
            log.info("no front fisheye h265 found under {}, skip preview for slice {}", sessionDirPath, sliceId);
            return;
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("fisheye-preview-");
            // 广角存广角名称，鱼眼存鱼眼名称
            String targetName = h265.getFileName().toString().startsWith(FRONT_WIDE_SENSOR)
                    ? FRONT_WIDE_VIDEO_NAME
                    : PREVIEW_VIDEO_NAME;
            Path mp4 = tempDir.resolve(targetName);
            convertToMp4(h265, mp4);
            storageAccess.put(StorageKeys.video(sliceId, targetName), mp4, "video/mp4");
            log.info("front preview generated (h265 fallback) for slice {}: {} -> {} ({} bytes)",
                    sliceId, h265.getFileName(), targetName, Files.size(mp4));
        } finally {
            if (tempDir != null) {
                deleteQuietly(tempDir);
            }
        }
    }

    /**
     * 在会话目录中按优先级查找前视 h265：先找广角 F100_front_wide*.h265，
     * 数据包中无广角视频流时降级找前视鱼眼 F195_front_fisheye*.h265。
     */
    private Path findFisheyeFile(String sessionDirPath) {
        Path sessionDir = Paths.get(sessionDirPath);
        if (!Files.isDirectory(sessionDir)) {
            return null;
        }

        log.info("scanning front camera dir: {}", sessionDir);
        for (String sensor : PREVIEW_SENSOR_CANDIDATES) {
            try (Stream<Path> files = Files.walk(sessionDir)) {
                List<Path> matches = files
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith(sensor))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                if (!matches.isEmpty()) {
                    log.info("scanning {} results: {} (picked {})",
                            sensor, matches.size(), matches.get(0).getFileName());
                    return matches.get(0);
                }
                log.info("scanning {} results: 0, falling back to next candidate", sensor);
            } catch (IOException e) {
                log.warn("scan dir for {} failed: {}", sensor, sessionDir, e);
            }
        }
        return null;
    }

    /**
     * ffmpeg 裸 HEVC 流 → H.264 mp4（与 Python cpu_mux_cmd 对齐）：
     * -f hevc 显式声明输入格式；
     * -vf scale=-2:1080 按目标高度 1080 缩放（宽度 -2 保持偶数对齐）；
     * -preset fast / -crf 23 / -pix_fmt yuv420p 确保浏览器可播放；
     * -movflags +frag_keyframe+empty_moov 生成分片 MP4（无初始 moov，适合流式拉取）。
     */
    private void convertToMp4(Path h265, Path mp4) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath, "-y",
                "-f", "hevc",
                "-i", h265.toString(),
                "-vf", "scale=-2:1080",
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "23",
                "-pix_fmt", "yuv420p",
                "-movflags", "+frag_keyframe+empty_moov",
                mp4.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // 读取输出避免缓冲区写满阻塞
        byte[] out = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(ffmpegTimeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ffmpeg timeout after " + ffmpegTimeoutMinutes + " minutes");
        }
        if (process.exitValue() != 0) {
            String tail = new String(out);
            int len = tail.length();
            throw new IOException("ffmpeg exited with " + process.exitValue() + ": "
                    + tail.substring(Math.max(0, len - 2000)));
        }
        if (!Files.exists(mp4) || Files.size(mp4) == 0) {
            throw new IOException("ffmpeg produced no output: " + mp4);
        }
    }

    /**
     * 递归复制目录（nio 实现；commons-io 未引入，避免新增依赖）
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(path -> {
                Path dest = target.resolve(source.relativize(path));
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力删除
                }
            });
        } catch (IOException ignored) {
            // 尽力删除
        }
    }

    /**
     * 清理预览工作区（copy + tar + bag 产物）
     */
    private void cleanupQuietly(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try {
            deleteQuietly(workDir);
            log.debug("Cleaned fisheye preview work dir: {}", workDir);
        } catch (Exception e) {
            log.warn("Failed to clean fisheye preview work dir {}: {}", workDir, e.getMessage());
        }
    }
}
