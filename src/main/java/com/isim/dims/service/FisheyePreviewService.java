package com.isim.dims.service;

import java.util.List;

/**
 * 前视预览视频服务
 * <p>
 * MMT 调度下载只拉 meta + 前视候选摄像头的 h265（meta-only 导入），
 * 由本服务把会话目录里的 mfbag 转成 bag 后抽帧、或把 h265 直接转码为浏览器可播放的 mp4，
 * 存入 videos/{sliceId}/preview_front_fisheye.mp4，
 * 通过既有 /data/videos/{sliceId}/{filename} 端点对外播放。
 * <p>
 * 摄像头优先级：数据包含 11 路视频流时取前视广角 {@value #FRONT_WIDE_SENSOR}；
 * 数据包中无广角视频流时降级播放前视鱼眼 {@value #FRONT_FISHEYE_SENSOR}。
 *
 * @feature: slice
 */
public interface FisheyePreviewService {

    /**
     * 预览视频文件名（存储 key: videos/{sliceId}/preview_front_fisheye.mp4）
     */
    String PREVIEW_VIDEO_NAME = "preview_front_fisheye.mp4";
    String FRONT_WIDE_VIDEO_NAME = "preview_front_wide.mp4";

    /**
     * 预览首选摄像头：前视广角 F100_front_wide（与 CfdiConstants.H265_SENSOR_MAP 中的键一致）
     */
    String FRONT_WIDE_SENSOR = "F100_front_wide";

    /**
     * 预览降级摄像头：数据包中无广角视频流时的前视鱼眼 F195_front_fisheye
     */
    String FRONT_FISHEYE_SENSOR = "F195_front_fisheye";

    /**
     * 预览候选摄像头（h265 文件名前缀），按优先级排列：广角优先、鱼眼兜底。
     */
    List<String> PREVIEW_SENSOR_CANDIDATES = List.of(FRONT_WIDE_SENSOR, FRONT_FISHEYE_SENSOR);

    /**
     * 同步生成预览视频：复用 CFDI 转换链路（复制 public/ → repackForDocker →
     * runConversion）把会话目录里的 mfbag 转成 bag，再从 bag 的前视鱼眼 topic
     * 抽帧 mux 成分片 MP4，写入 videos/{sliceId}/preview_front_fisheye.mp4。
     * 转换不可用（如会话内无 mfbag）时回退为对会话目录里的前视鱼眼 h265 直接 ffmpeg 转码。
     * 找不到鱼眼数据或转码失败时仅记日志，不抛异常（预览是尽力而为的附加能力）。
     *
     * @param sliceId        切片 ID
     * @param sessionDirPath 会话目录（BOS 下载到本地的目录）
     */
    void generatePreview(String sliceId, String sessionDirPath);

    /**
     * 异步生成预览视频（meta-only 导入后调用，避免阻塞调度线程）。
     */
    void generatePreviewAsync(String sliceId, String sessionDirPath);

    /**
     * 预览视频是否已生成。
     */
    boolean hasPreview(String sliceId);
}
