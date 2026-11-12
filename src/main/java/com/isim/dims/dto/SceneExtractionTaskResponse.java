package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 场景提取任务详情
 *
 * 对应 OpenAPI schema: SceneExtractionTask。
 * required: [task_id, slice_id, status, created_at]
 *
 * 状态机：PENDING → RUNNING → SUCCESS | FAILED
 *  - SUCCESS 时 scene_count / output_path 有值
 *  - FAILED 时 error_msg 非空
 *
 * <p><b>整包导出模式（当前实现）</b>：流水线不再运行 scene_detector.py，
 * output_path 指向主产物 scene_data/{sliceId}/openscenario/{sliceId}.xosc，
 * 而 ego_trajectory_raw.json / perception/ / scene_data.json / carla/ 等中间产物
 * 是 openscenario/ 的<b>同级兄弟</b>，位于 scene_data/{sliceId}/ 下。
 * 因此 dirname(output_path) <b>不等于</b>产物根目录（早先 detected_scenes.json
 * 时代的不变量已失效）。</p>
 *
 * <p>取过程文件路径请优先用 intermediate_paths（每一项即真实存储引用），
 * 不要自行从 output_path 拼目录；intermediate_paths 与 output_path 同 key 空间
 * （local 绝对路径 / minio 相对 key）。
 * 前端不可直接访问该路径，仅作状态提示；读取内容使用 readProcessFile
 * （path 参数接受 intermediate_paths 原值，后端在 key 空间做 allow-list 前缀校验）。
 *
 * @feature: ingestion
 */
@Data
public class SceneExtractionTaskResponse {

    /** 任务 UUID */
    private String taskId;

    /** 切片 UUID */
    private String sliceId;

    /** PENDING|RUNNING|SUCCESS|FAILED（SceneExtractionStatus 枚举） */
    private String status;

    /** 进度 0-100 */
    private Integer progress;

    /** 检测到的场景总数，SUCCESS 时有值 */
    private Integer sceneCount;

    /** 主产物 openscenario/{sliceId}.xosc 存储引用（local 绝对路径 / minio 相对 key），SUCCESS 时有值 */
    private String outputPath;

    /** 错误信息，FAILED 时非空 */
    private String errorMsg;

    /** 任务创建时间，UTC RFC3339 */
    private String createdAt;

    /** 任务结束时间，UTC RFC3339；未结束时为 null */
    private String finishedAt;

    /** 过程文件存储引用列表（JSON 数组；local 绝对路径 / minio 相对 key），SUCCESS 时有值 */
    private List<String> intermediatePaths;
}
