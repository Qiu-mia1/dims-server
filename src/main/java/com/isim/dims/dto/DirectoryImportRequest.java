package com.isim.dims.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 本地目录导入请求参数
 *
 * 对应 POST /v1/data-slices/directory-import 的请求体。
 * OpenAPI schema: DirectoryImportRequest
 * 扫描服务器本地目录下符合 MD5 命名的子目录，批量创建导入任务。
 * @feature: ingestion
 */
@Data
public class DirectoryImportRequest {

    /** 服务器本地绝对路径（必填），如 /home/nnn/project/JMC/data/jiangling-MMT-CX835-ICA/20260709 */
    @NotBlank(message = "directory is required")
    private String directory;

    /** 数据来源提供方（必填）：MMT | ZYT | Hirain */
    @NotBlank(message = "source is required")
    private String source;

    /** 切片名称前缀，可选；为空时默认取目录名 */
    private String name;

    /** 切分名称：项目名称（数据浏览器命名），为空时默认 CX835ICA */
    private String projectName;

    /** 切分名称：录制工具（数据浏览器命名），为空时默认 CFDI */
    private String recordingTool;

    /** 切分名称：触发类型（数据浏览器命名），为空时默认 disabled */
    private String triggerType;

    /** strict 模式：true=任一会话校验失败整体 400；false=跳过失败会话继续导入（默认） */
    private boolean strict = false;

    /**
     * meta-only 模式（MMT 调度用）：true=只解析 meta 建切片（status=META_READY），
     * 不校验 mfbag、不跑导入流水线，并异步生成前视鱼眼预览视频；
     * 用户后续在数据浏览器点「加载」时再全量拉取并走完整导入。
     */
    private boolean metaOnly = false;

    /**
     * meta-only 模式必填：本目录对应的 BOS 对象前缀（不含 localDir），
     * 如 {bosPrefix}/{yyyyMMdd}/{md5}；切片会记录 import_bos_key =
     * bosKeyPrefix/{sessionDir}，供按需全量拉取使用。
     */
    private String bosKeyPrefix;
}
