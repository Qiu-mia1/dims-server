package com.isim.dims.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储配置（MinIO 迁移）
 *
 * <p>从 application.yml 读取 dims.storage.* 配置项。
 * 所有文件 I/O 操作通过此配置获取目录路径，避免硬编码。</p>
 *
 * <p>默认值：
 * <ul>
 *   <li>type: local — 存储模式：local（本地文件系统，默认）/ minio（MinIO 对象存储）</li>
 *   <li>tempDir: ${user.dir}/data/tmp — minio 模式本地临时目录（Python 产物 staging、bag 物化缓存）</li>
 *   <li>bagDir: ${user.dir}/data — ROS bag 原始文件</li>
 *   <li>exportDir: ${user.dir}/data/exports — 导出产物</li>
 *   <li>videoDir: ${user.dir}/data/videos — 视频段缓存</li>
 *   <li>trajectoryDir: ${user.dir}/data/trajectories — GPS 轨迹缓存</li>
 *   <li>signalDir: ${user.dir}/data/signals — 信号数据缓存</li>
 *   <li>replayCacheDir: ${user.dir}/data/cache — 2D 回放产物缓存（障碍分段/消息 JSONL）</li>
 * </ul>
 * </p>
 * @feature: infra
 */
@Component
@ConfigurationProperties(prefix = "dims.storage")
@Data
public class StorageConfig {

    /** 存储模式：local / minio（决定 StorageService 实现装配，密钥经环境变量注入） */
    private String type = "local";

    /** minio 模式本地临时目录：Python 产物 staging + bag 物化缓存 + 临时工作目录 */
    private String tempDir = "/data/tmp";

    /** ROS bag 原始文件存储目录 */
    private String bagDir = System.getProperty("user.dir") + "/data";

    /** 导出产物存储目录 */
    private String exportDir = System.getProperty("user.dir") + "/data/exports";

    /** 视频段缓存目录（Phase 2 可视化） */
    private String videoDir = System.getProperty("user.dir") + "/data/videos";

    /** GPS 轨迹缓存目录（Phase 2 可视化） */
    private String trajectoryDir = System.getProperty("user.dir") + "/data/trajectories";

    /** 信号数据缓存目录（Phase 2 可视化） */
    private String signalDir = System.getProperty("user.dir") + "/data/signals";

    /** CFDI 数据包工作目录（Phase 2 CFDI 导入） */
    private String cfdiDir = "/data/tmp/cfdi";

    /** 2D 回放产物缓存目录（障碍 10s 分段 / Raw 消息 JSONL，24h 过期，切片删除级联清理） */
    private String replayCacheDir = System.getProperty("user.dir") + "/data/cache";
}
