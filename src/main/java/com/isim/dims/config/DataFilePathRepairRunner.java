package com.isim.dims.config;

import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时数据修复：修正 data_slice.file_path 指向失效文件的存量数据
 *
 * <p>背景：CFDI 导入流水线在转换完成后会更新 file_path 指向
 * {@code {bagDir}/{sliceId}/original.bag}，但早期版本缺失该逻辑，
 * 导致存量 CFDI 切片的 file_path 仍指向已删除的
 * {@code {cfdiDir}/{sliceId}/original.tar.gz}，所有依赖 bag 文件的
 * 提取（GPS 轨迹/视频/信号）报 "Bag file not found"。</p>
 *
 * <p>修复策略：对 file_path 为空或存储层不存在的切片，若标准产物 key
 * {@code slices/{id}/original.bag} 存在则修正 file_path（修正为相对 key，
 * 存量绝对路径亦被存储层兼容）。
 * 幂等且安全：候选文件必须真实存在才会写入，不会破坏正常数据。</p>
 *
 * <p>仅 local 模式装配（MinIO 迁移）：minio 模式下存量 19GB 本地数据只归档不迁移，
 * 对象存储中不存在可回写的产物，自愈无意义且会污染 file_path。</p>
 * @feature: infra
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dims.storage.type", havingValue = "local", matchIfMissing = true)
public class DataFilePathRepairRunner implements ApplicationRunner {

    private final DataSliceMapper dataSliceMapper;
    private final StorageAccess storageAccess;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Data file_path repair started (storage mode=local)");

        int repaired = 0;
        int invalid = 0;

        List<DataSlice> slices = dataSliceMapper.selectList(null);
        for (DataSlice slice : slices) {
            String storedPath = slice.getFilePath();

            // 路径有效（存储层存在且非空）→ 跳过
            if (storedPath != null && !storedPath.isBlank() && storageAccess.exists(storedPath)) {
                continue;
            }

            // 路径失效 → 尝试标准产物 key slices/{id}/original.bag
            String candidateKey = StorageKeys.originalBag(slice.getId());
            if (!storageAccess.exists(candidateKey)) {
                invalid++;
                log.warn("file_path invalid and no candidate found, skipped: sliceId={}, stored={}",
                        slice.getId(), storedPath);
                continue;
            }

            log.info("Repairing file_path: sliceId={}, sourceType={}, from={}, to={}",
                    slice.getId(), slice.getSourceType(), storedPath, candidateKey);
            slice.setFilePath(candidateKey);
            dataSliceMapper.updateById(slice);
            repaired++;
        }

        log.info("Data file_path repair finished: repaired={}, unresolved={}, total={}",
                repaired, invalid, slices.size());
    }
}
