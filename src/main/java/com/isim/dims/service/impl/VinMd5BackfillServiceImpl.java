package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.VinMd5MappingMapper;
import com.isim.dims.service.VinMd5BackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * VIN-MD5 映射导入后的历史切片 VIN 回写实现
 *
 * <p>一条 SQL 完成：以 {@code vin_md5_mapping} 为更新源，按 {@code import_md5}
 * 关联到 {@code data_slice}，把 {@code vin} 与 {@code vin_md5_id} 一次写好。
 * 全程在数据库侧完成，不把切片记录拉回 Java 端。</p>
 *
 * <pre>
 * UPDATE data_slice d SET vin = v.vin, vin_md5_id = v.id
 *   FROM vin_md5_mapping v
 *  WHERE d.import_md5 = v.md5 AND v.md5 IN (...)
 *    AND (d.vin IS DISTINCT FROM v.vin OR d.vin_md5_id IS DISTINCT FROM v.id);
 * </pre>
 *
 * @feature: ingestion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VinMd5BackfillServiceImpl implements VinMd5BackfillService {

    /**
     * 单批 MD5 数量：控制 IN 列表长度，避免 SQL 过长（与导入批次一致）
     */
    private static final int CHUNK_SIZE = 500;

    private final DataSliceMapper dataSliceMapper;
    private final VinMd5MappingMapper vinMd5MappingMapper;

    /**
     * 异步回写：失败只记日志，不向上传播（导入结果已返回给用户，不该被回写拖垮）
     */
    @Override
    @Async("vinBackfillExecutor")
    public void backfillAsync(List<String> md5s) {
        if (md5s == null || md5s.isEmpty()) {
            return;
        }
        int updated = 0;
        for (int i = 0; i < md5s.size(); i += CHUNK_SIZE) {
            List<String> chunk = md5s.subList(i, Math.min(i + CHUNK_SIZE, md5s.size()));
            try {
                updated += dataSliceMapper.backfillVinByImportMd5(chunk);
            } catch (Exception e) {
                // 单批失败不影响其余批次，也不影响导入主流程
                log.error("vin backfill: batch failed: size={}, msg={}", chunk.size(), e.getMessage(), e);
            }
        }
        log.info("vin backfill done: md5Count={}, slicesUpdated={}", md5s.size(), updated);
    }
}
