package com.isim.dims.service;

import com.isim.dims.dto.VinMd5ImportResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 车辆信息（VIN-MD5 映射）Excel 导入服务
 *
 * <p>上传的 Excel 必须包含 vin、md5 两列（列名忽略大小写、忽略首尾空格），
 * 逐行解析后按 vin 去重并批量 upsert 到 {@code vin_md5_mapping}：
 * 已存在的 VIN 覆盖其 MD5，新的 VIN 直接插入。</p>
 *
 * <p>用途：本地目录导入按 MD5 子目录反查 VIN 时，映射缺失会整批跳过
 * （MD5_NOT_MAPPED），本服务用于批量补齐/刷新这份映射。</p>
 *
 * @feature: ingestion
 */
public interface VinMd5ImportService {

    /**
     * 解析 Excel 并写入 VIN-MD5 映射
     *
     * @param file 上传的 Excel（.xlsx / .xls），须含 vin、md5 两列
     * @return 导入结果（总行数 / 成功数 / 失败明细）
     * @throws IllegalArgumentException 文件为空或缺少 vin/md5 列（→ 400）
     * @throws IllegalStateException    Excel 解析失败（→ 500）
     */
    VinMd5ImportResponse importExcel(MultipartFile file);
}
