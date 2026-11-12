package com.isim.dims.util;

/**
 * 存储 Key 规范（MinIO 迁移）
 *
 * <p>统一构造所有存储对象 key，禁止业务层拼接字符串。
 * 约定 key 一律使用 "/" 分隔、无前导斜杠：</p>
 * <pre>
 *   slices/{id}/original.bag       ROS bag 原始文件
 *   slices/{id}/original.zip       Hirain（恒润）源 mcap_converter 转换产物（.h265 等打包后的 zip）
 *   slices/{id}/original.tar.gz    CFDI 原始压缩包
 *   cfdi/{id}/...                  CFDI 工作产物
 *   exports/{id}/{exportId}/output.bag   导出产物
 *   videos/{id}/{file}             视频段缓存
 *   signals/{id}/signals.json      信号数据
 *   trajectories/{id}/trajectory.json     GPS 轨迹
 *   replay/{id}/replay/...         2D 回放产物
 *   scene_data/{id}/...            场景提取产物
 *   cockpit/{id}/...               驾驶舱统计产物
 *   tasks/{taskId}.json            任务元数据
 * </pre>
 * @feature: infra
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    public static String originalBag(String sliceId) {
        return "slices/" + sliceId + "/original.bag";
    }

    /**
     * Hirain（恒润）源数据经 mcap_converter 转换后的产物包：
     * 转换输出的所有 .h265 文件打包成一个 zip 后上传，data_slice.file_path 指向本 key。
     */
    public static String originalZip(String sliceId) {
        return "slices/" + sliceId + "/original.zip";
    }

    public static String originalTarGz(String sliceId) {
        return "slices/" + sliceId + "/original.tar.gz";
    }

    public static String cfdi(String sliceId, String fileName) {
        return "cfdi/" + sliceId + "/" + fileName;
    }

    public static String export(String sliceId, String exportId, String fileName) {
        return "exports/" + sliceId + "/" + exportId + "/" + fileName;
    }

    public static String exportPrefix(String sliceId) {
        return "exports/" + sliceId + "/";
    }

    public static String video(String sliceId, String fileName) {
        return "videos/" + sliceId + "/" + fileName;
    }

    public static String videosPrefix(String sliceId) {
        return "videos/" + sliceId + "/";
    }

    public static String videoImagesPrefix(String sliceId) {
        return "images/" + sliceId + "/";
    }

    public static String videoImagesTopicDir(String sliceId, String topicSafe) {
        return "images/" + sliceId + "/" + topicSafe + "/";
    }

    public static String videoImagesIndex(String sliceId, String topicSafe) {
        return "images/" + sliceId + "/" + topicSafe + "/index.json";
    }

    public static String signals(String sliceId) {
        return "signals/" + sliceId + "/signals.json";
    }

    public static String trajectory(String sliceId) {
        return "trajectories/" + sliceId + "/trajectory.json";
    }

    public static String replayPrefix(String sliceId) {
        return "replay/" + sliceId + "/";
    }

    public static String replayKindPrefix(String sliceId, String kind) {
        return "replay/" + sliceId + "/replay/" + kind + "/";
    }

    public static String replayIndex(String sliceId, String kind) {
        return replayKindPrefix(sliceId, kind) + "index.json";
    }

    public static String replayMessagesPrefix(String sliceId) {
        return replayKindPrefix(sliceId, "messages");
    }

    public static String replayMessagesIndex(String sliceId, String topicSafe) {
        return replayKindPrefix(sliceId, "messages") + topicSafe + "/index.json";
    }

    /**
     * ROSData 回放数据（extract_replay.py 产物，api.md ROSData 顶层 JSON，
     * 含 allTimestamps / objectMessages / lineMessages / planningTrajectoryMessages 等）
     */
    public static String replayRosData(String sliceId) {
        return replayKindPrefix(sliceId, "relay_plus") + "rosdata.json";
    }

    public static String replayMessageFile(String sliceId, String topicSafe, String file) {
        return replayKindPrefix(sliceId, "messages") + topicSafe + "/" + file;
    }

    public static String replayCalibration(String sliceId) {
        return replayKindPrefix(sliceId, "") + "calibration.json";
    }

    public static String sceneDataPrefix(String sliceId) {
        return "scene_data/" + sliceId + "/";
    }

    public static String cockpitStats(String sliceId) {
        return "cockpit/" + sliceId + "/cockpit_stats.json";
    }

    public static String cockpitPrefix(String sliceId) {
        return "cockpit/" + sliceId + "/";
    }

    public static String task(String taskId) {
        return "tasks/" + taskId + ".json";
    }

    public static String tasksPrefix() {
        return "tasks/";
    }
}
