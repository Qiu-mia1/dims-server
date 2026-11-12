package com.isim.dims.service;

import java.nio.file.Path;

/**
 * CFDI 数据包转换 Service
 *
 * 负责 CFDI tar.gz 数据包的解压、重组、Docker 转换、产物归位、临时文件清理。
 * 每个切片独立执行，互不干扰。
 *
 * <p>MinIO 迁移：输入为存储引用（tarRef），解压前先物化到本地工作区
 * （cfdiWorkDir：local → cfdiDir/{sliceId}，minio → tempDir/cfdi/{sliceId}）；
 * 转换产物（bag/h265）经存储层写回（local → 本地目录，minio → 对象存储）。</p>
 * @feature: cfdi
 */
public interface CfdiConversionService {

    /**
     * 解压 tar.gz 到 cfdi 工作目录
     *
     * @param sliceId 切片 UUID
     * @param tarRef  tar.gz 存储引用（local 模式可为绝对路径或相对 key，minio 模式为相对 key）
     * @return 解压后的 public/ 目录路径
     */
    Path extractTarGz(String sliceId, String tarRef);

    /**
     * 在 public/ 下打包 mapping_input.tar.gz
     *
     * @param publicDir public/ 目录路径
     * @return 打包后的 tar.gz 文件路径
     */
    Path repackForDocker(Path publicDir);

    /**
     * 执行 Docker 容器转换
     *
     * @param tarFile mapping_input.tar.gz 文件路径
     * @param workDir 工作目录（挂载点）
     * @return 转换生成的 mapping_input.bag 文件路径
     */
    Path runConversion(Path tarFile, Path workDir);

    /**
     * 转换产物 mapping_input.bag 写回持久存储（local → bagDir/{sliceId}/original.bag；
     * minio → 对象存储 slices/{id}/original.bag）
     *
     * @param bagPath mapping_input.bag 文件路径
     * @param sliceId 切片 UUID
     * @return bag 存储引用（统一相对 key "slices/{id}/original.bag"，两模式一致）
     */
    String copyBagToStorage(Path bagPath, String sliceId);

    /**
     * 复制生成的 h265 视频文件到 videoDir/{sliceId}/
     *
     * 文件名前缀 → sensor_name 映射：
     * F30_front_far, F50_rear_mid, F100_front_wide,
     * F100_left_front, F100_left_rear, F100_right_front, F100_right_rear,
     * F195_front_fisheye, F195_left_fisheye, F195_rear_fisheye, F195_right_fisheye,
     * latch
     *
     * @param publicDir public/ 目录路径（包含 generated/topics/images/）
     * @param sliceId 切片 UUID
     */
    void copyH265Videos(Path publicDir, String sliceId);

    /**
     * 清理 cfdi 临时工作目录
     *
     * @param sliceId 切片 UUID
     */
    void cleanupTempDir(String sliceId);
}
