package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.ReplayRosDataService;
import com.isim.dims.util.PythonExecutor;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 回放 rosdata.json 提取实现。
 *
 * <p>从原 {@code IngestionPipelineServiceImpl.extractReplayRosData} 抽取，行为严格等价：</p>
 * <ul>
 *   <li>调 Python 脚本 {@code extract_replay.py}（JSON 参数模式，{@code --input}），
 *       入参含 {@code slice_id} + {@code bag_path}。</li>
 *   <li>超时 3600s，进程 stdout drain & 退出码检查。</li>
 *   <li>脚本侧 JSON 序列化产物路径 = {@code localBag.parent/replay/relay_plus/rosdata.json}，
 *       成功判断双校验：{@code result.success=true} AND {@code Files.exists(rosdataPath)}。</li>
 *   <li>minio 模式：上传 {@code StorageKeys.replayRosData(sliceId)}；local 模式：拷贝到
 *       {@code storageAccess.resolveLocalPath(...)} 落地，路径与 storage_key 同源。</li>
 * </ul>
 *
 * <p>定时重跑入口（{@link #reExtractBySliceId(String)}）从 DB 拿切片、调
 * {@code StorageAccess.materializeLocal} 物化 bag、走同一 {@code doExtract}。
 * 仅在失败时写 {@code error_msg}（成功时一并清空，避免脏记），成功/失败均刷新
 * {@code updated_at}（MP {@code FieldFill=INSERT_UPDATE} 自动）。</p>
 *
 * <p>2026-09 用户反馈：不新建 DB 字段（如 {@code replay_status} 等），完全复用现有字段。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayRosDataServiceImpl implements ReplayRosDataService {

    /**
     * Python 提取脚本（相对工作目录 dims-python/）
     */
    private static final String EXTRACT_SCRIPT = "extract_replay.py";

    /**
     * Python 提取超时（回放 rosdata 包含 messages / trajectories，体量大）
     */
    private static final int EXTRACT_TIMEOUT_SEC = 3600;

    private final PythonExecutor pythonExecutor;
    private final StorageAccess storageAccess;
    private final DataSliceMapper dataSliceMapper;
    private final ObjectMapper objectMapper;

    // ============== 导入流水线主路径（软失败） ==============

    @Override
    public void extractOnIngestion(String sliceId, String localBag) {
        try {
            doExtract(sliceId, localBag);
        } catch (Exception e) {
            // 软失败：与原内联实现一致，warn 后吞掉，不阻塞导入流水线
            log.warn("[replay] extractOnIngestion sliceId={} 失败（已忽略，不阻塞导入）: {}",
                    sliceId, e.getMessage(), e);
        }
    }

    // ============== 定时任务重跑入口（更新现有字段） ==============

    @Override
    public void reExtractBySliceId(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            log.warn("[replay] reExtract sliceId={} 切片不存在，跳过", sliceId);
            return;
        }
        try {
            Path localBag = storageAccess.materializeLocal(slice.getFilePath());
            if (localBag == null || !Files.exists(localBag)) {
                throw new IOException("bag 物化失败或不存在: " + slice.getFilePath());
            }
            doExtract(sliceId, localBag.toString());
            markSliceSuccess(slice);
            log.info("[replay] reExtract sliceId={} 成功", sliceId);
        } catch (Exception e) {
            log.warn("[replay] reExtract sliceId={} 失败: {}", sliceId, e.getMessage(), e);
            markSliceFailed(slice, e);
        }
    }

    // ============== 共用提取实现 ==============

    /**
     * 实际提取 rosdata.json 并落盘（导入 / 重跑共用）。
     *
     * <p>行为与原 {@code IngestionPipelineServiceImpl.extractReplayRosData} 严格等价。</p>
     */
    private void doExtract(String sliceId, String localBag) throws IOException {
        Path outDir = Path.of(localBag).getParent()
                .resolve("replay").resolve("relay_plus");
        Files.createDirectories(outDir);
        Path rosdataPath = outDir.resolve("rosdata.json");
        // 兜底清理旧产物，避免上次失败残留
        Files.deleteIfExists(rosdataPath);

        Map<String, Object> args = new HashMap<>();
        // 与 dims-python/extract_replay.py argparse 约定：bag_path + output_path 为必填，
        // timestamp_unit 与 parse_rosbag.py 对齐（设备消息时间戳单位 = ns）。
        args.put("bag_path", localBag);
        args.put("output_path", rosdataPath.toString());
        args.put("timestamp_unit", "ns");
        log.debug("[replay] doExtract sliceId={} bag={} output={}", sliceId, localBag, rosdataPath);
        // 全量消息解析（目标物/车道线/规划轨迹/信号灯等，2 Hz × 全时长），
        // 耗时高于 parse_rosbag，超时取 3600s
        String result = pythonExecutor.execute("extract_replay.py", args, 3600);

        Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
        if (!Boolean.TRUE.equals(resultMap.get("success"))) {
            throw new RuntimeException("extract_replay.py failed: " + result);
        }

        // 落盘：minio 模式上传；local 模式拷贝到 StorageAccess 规范路径
        String outputKey = StorageKeys.replayRosData(sliceId);
        if (storageAccess.isMinioMode()) {
            storageAccess.put(outputKey, rosdataPath, "application/json");
        } else {
            Path target = storageAccess.resolveLocalPath(outputKey);
            Files.createDirectories(target.getParent());
            Files.copy(rosdataPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 成功标记：清空 error_msg，updated_at 由 MP FieldFill=INSERT_UPDATE 自动刷新。
     */
    private void markSliceSuccess(DataSlice slice) {
        if (slice.getErrorMsg() == null) {
            return; // 无脏记、无需更新
        }
        DataSlice upd = new DataSlice();
        upd.setId(slice.getId());
        upd.setErrorMsg(null);
        dataSliceMapper.updateById(upd);
    }

    /**
     * 失败标记：把错误摘要写入 error_msg（截断避免超长），updated_at 由 MP FieldFill 刷新。
     */
    private void markSliceFailed(DataSlice slice, Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (msg.length() > 1000) {
            msg = msg.substring(0, 1000) + "...";
        }
        LambdaUpdateWrapper<DataSlice> uw = new LambdaUpdateWrapper<>();
        uw.eq(DataSlice::getId, slice.getId()).set(DataSlice::getErrorMsg, msg);
        dataSliceMapper.update(null, uw);
    }
}