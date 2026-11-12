package com.isim.dims.service;

/**
 * CFDI 元数据解析 Service
 *
 * 负责解析 CFDI 数据包内的摄像头标定参数和元数据，
 * 并将 sub_span_us 分发到 sensor_stream 记录。
 * @feature: cfdi
 */
public interface CfdiMetadataService {

    /**
     * 解析并入库摄像头标定参数
     *
     * 调用 Python parse_cfdi_calibration.py 解析 public/data/other/camera_*.yaml.gz
     * 返回的 JSON 数组逐条写入 camera_calibration 表。
     *
     * @param sliceId  切片 UUID
     * @param publicDir public/ 目录路径
     */
    void parseCameraCalibration(String sliceId, String publicDir);

    /**
     * 解析并入库 CFDI 元数据
     *
     * 调用 Python parse_cfdi_metadata.py 解析 meta/ 目录下的多个文件，
     * 返回结构化 JSON 写入 cfdi_meta 表。
     *
     * @param sliceId  切片 UUID
     * @param cfdiDir  cfdi 工作目录（含 meta/ 子目录）
     */
    void parseCfdiMetadata(String sliceId, String cfdiDir);

    /**
     * 将 sub_span_us 分发到 sensor_stream 记录
     *
     * 从 cfdi_meta 中提取 sub_span_us，通过 sensor_name → topic 映射
     * 更新匹配的 sensor_stream 记录的 duration_us 字段。
     *
     * @param sliceId 切片 UUID
     */
    void distributeSubSpanUs(String sliceId);
}
