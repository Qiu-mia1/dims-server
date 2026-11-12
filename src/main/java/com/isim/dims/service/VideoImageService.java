package com.isim.dims.service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 视频帧图浏览服务（每 5 秒存一张的影片关键帧）
 *
 * <p>读取视频提取流水线产出的 images/{sliceId}/{topicSafe}/index.json，
 * 提供按切片浏览帧图、按地址读取单张图片、以及全库扫描帧图（供语义检索）能力。</p>
 * @feature: visualization
 */
public interface VideoImageService {

    /**
     * 列出指定切片的所有 5 秒帧图
     *
     * @param sliceId 切片 UUID
     * @return 每项含 slice_id/topic/topic_safe/timestamp/file_name/url
     */
    List<Map<String, Object>> listSliceImages(String sliceId);

    /**
     * 读取单张帧图字节流（local/minio 统一）
     *
     * @param sliceId   切片 UUID
     * @param topicSafe 清洗后的 camera topic
     * @param fileName  帧图文件名（frame_<ns>.jpg）
     */
    InputStream openImage(String sliceId, String topicSafe, String fileName);

    /**
     * 扫描全库所有切片的帧图（语义检索用）
     *
     * @return 帧图条目列表，每项含 slice_id/topic/topic_safe/timestamp/file_name
     */
    List<Map<String, Object>> scanAllImages();
}