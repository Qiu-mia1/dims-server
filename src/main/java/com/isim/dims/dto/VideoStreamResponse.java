package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 视频流列表响应
 *
 * 对应 GET /v1/data-slices/{sliceId}/video-streams 的响应。
 * 契约格式：{ "video_streams": [{ "sensor_name", "video_file", "width", "height", "fps" }] }
 * @feature: visualization
 */
@Data
public class VideoStreamResponse {

    /** 视频流列表 */
    @JsonProperty("video_streams")
    private List<VideoStreamItem> videoStreams;

    /**
     * 视频流信息
     */
    @Data
    public static class VideoStreamItem {

        /** 传感器名称，如 "F30_front_far" */
        @JsonProperty("sensor_name")
        private String sensorName;

        /** 传感器流 Topic，如 "/sensor/camera_front_far/video"（来自 sensor_stream.topic） */
        @JsonProperty("topic")
        private String topic;

        /** 视频文件名，如 "F30_front_far.h265" */
        @JsonProperty("video_file")
        private String videoFile;

        /** 视频宽度（像素），如 3840 */
        private Integer width;

        /** 视频高度（像素），如 2160 */
        private Integer height;

        /** 帧率，如 10.0 */
        private Double fps;

        /** 可直接播放的视频静态地址（相对路径），如 "/data/videos/{sliceId}/_sensor_camera_front_far_video.mp4" */
        @JsonProperty("url")
        private String url;

        /** 视频文件是否已提取（存在于磁盘） */
        @JsonProperty("extracted")
        private Boolean extracted;
    }
}
