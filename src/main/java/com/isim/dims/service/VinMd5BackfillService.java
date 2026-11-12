package com.isim.dims.service;

import java.util.List;

/**
 * VIN-MD5 映射导入后的历史切片 VIN 回写服务
 *
 * <p>车辆信息 Excel 只维护 {@code vin_md5_mapping}。而本地目录导入时若这份
 * 映射还没建好，已入库的 {@code data_slice} 会留下 {@code vin IS NULL}
 * （或兜底成 {@code vin = md5, vin_md5_id = 1}）的历史数据。
 * 本服务在映射补齐后，按 {@code import_md5} 反查映射表把这些切片批量纠正。</p>
 *
 * <p>放在独立 Bean 中是为了让 {@code @Async} 走 Spring AOP 代理生效
 * （同类自调用不会被拦截）。</p>
 *
 * @feature: ingestion
 */
public interface VinMd5BackfillService {

    /**
     * 异步回写切片 VIN / 车辆外键
     *
     * <p>仅处理入参给出的 MD5：本次导入失败的行（典型：MD5 已被其它 VIN 占用）
     * 不参与回写，避免把别人的车错挂到别的 VIN 上。</p>
     *
     * <p>回写为幂等操作（{@code IS DISTINCT FROM} 只更新真正不一致的行），
     * 重复触发不会有副作用；失败仅记日志，不影响导入主流程已返回的结果。</p>
     *
     * @param md5s 本次导入成功的 bag 文件 MD5 清单（已归一化小写）
     */
    void backfillAsync(List<String> md5s);
}
