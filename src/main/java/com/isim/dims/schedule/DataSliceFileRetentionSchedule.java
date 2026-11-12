package com.isim.dims.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.impl.LocalDirectoryImportServiceImpl;
import com.isim.dims.service.impl.StorageAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 数据切片文件保留清理调度（仅删文件，不删记录）
 *
 * <p>每天定时把「{@code start_ts} 早于 {@code now - days} 天」的切片所占用磁盘/对象存储文件清掉：
 * <ol>
 *   <li>{@link DataSlice#getImportSourceDir()} 指向的本地导入源目录（递归删除目录及其下文件）；</li>
 *   <li>{@link DataSlice#getFilePath()} 指向的对象存储文件（minio 模式下的 bag 对象）。</li>
 * </ol>
 * <b>不删除 data_slice 记录、不修改任何字段</b>：元数据与看板口径保持不变，
 * 需要在保留期内重新取回文件时，由人工重新导入或经 BOS 恢复（见 {@code BosDirectoryRestoreService}）。</p>
 *
 * <p>同一 Bean 内还承载<b>历史切片 meta 字段回填</b>任务
 * （{@link #backfillSliceMetaFields()}）：只补齐 event_name / trigger_time 的空值，
 * 不覆盖已有值、不改其它字段 —— 与清理任务共用源目录白名单与 dry-run 配置。</p>
 *
 * <h3>幂等与安全</h3>
 * <ul>
 *   <li>不写库 ⇒ 每次调度都会重新扫描全部「已过期」记录，删除动作本身幂等
 *       （目录/对象不存在时直接跳过），因此只会重复扫描、不会误删。</li>
 *   <li>递归删除前强校验 {@code import_source_dir} 位于
 *       {@code dims.retention.slice.import-source-roots} 之下且不等于该根目录本身，
 *       防止脏数据（如 {@code /}、{@code /data}）导致误删整盘。</li>
 *   <li>跳过进行中的切片（{@code UPLOADING|CONVERTING|PARSING|TAGGING}），
 *       避免把正在导入/转换的数据删掉。</li>
 *   <li>minio 模式下 {@code storage_type=LOCAL} 的归档记录不删对象存储
 *       （其数据本就在本地归档，不在 minio 里）。</li>
 *   <li>单条失败不影响其余记录：异常按切片粒度捕获并计入失败数。</li>
 * </ul>
 *
 * <h3>配置（application.yml → dims.retention.slice）</h3>
 * <pre>
 * dims:
 *   retention:
 *     slice:
 *       enabled: true               # 总开关（false 时整 Bean 不装配）
 *       days: 30                    # 保留天数：start_ts 早于 now-days 的切片文件可被清理
 *       cron: 0 30 3 * * ?          # 默认每天 03:30
 *       batch-size: 200             # 单批扫描条数（清理 / meta 回填共用）
 *       dry-run: false              # true = 只统计不删除、不写库
 *       import-source-roots: /data/downloads   # 允许递归删除/读取的源目录白名单（逗号分隔）
 *       meta-fixup:
 *         cron: 0 0 4 * * ?         # 历史切片 meta 字段回填（默认每天 04:00；设为 "-" 关闭该任务）
 * </pre>
 *
 * @feature: infra
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dims.retention.slice.enabled", havingValue = "true", matchIfMissing = true)
public class DataSliceFileRetentionSchedule {

    /**
     * 进行中的切片状态：文件仍可能被流水线读写，不参与清理
     */
    private static final Set<String> IN_FLIGHT_STATUSES =
            Set.of("UPLOADING", "CONVERTING", "PARSING", "TAGGING");

    /**
     * 分布式锁 key：多副本部署下同一时刻只允许一个实例执行清理
     */
    private static final String LOCK_KEY = "lock:dataSliceFileRetentionCleanup";

    /**
     * 分布式锁 key：多副本部署下同一时刻只允许一个实例执行 meta 字段回填
     */
    private static final String META_FIXUP_LOCK_KEY = "lock:dataSliceMetaFieldsBackfill";

    /**
     * meta 文件名：会话目录（{@code import_source_dir}）下无扩展名的元信息文件，内容为 JSON
     */
    private static final String META_FILE_NAME = "meta";

    private final DataSliceMapper dataSliceMapper;
    private final StorageAccess storageAccess;
    private final RedissonClient redissonClient;

    /**
     * 保留天数
     */
    @Value("${dims.retention.slice.days:30}")
    private int retentionDays;

    /**
     * 单批扫描条数
     */
    @Value("${dims.retention.slice.batch-size:200}")
    private int batchSize;

    /**
     * 试运行：只统计不删除
     */
    @Value("${dims.retention.slice.dry-run:false}")
    private boolean dryRun;

    /**
     * 允许递归删除的本地源目录白名单（逗号分隔）
     */
    @Value("${dims.retention.slice.import-source-roots:/data/downloads}")
    private String importSourceRoots;

    /**
     * 单实例重入保护（上一次调度未跑完时跳过本次）
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 单实例重入保护（meta 字段回填：上一次调度未跑完时跳过本次）
     */
    private final AtomicBoolean metaFixupRunning = new AtomicBoolean(false);

    /**
     * 定时清理过期切片的源目录与对象存储文件（不删 DB 记录）。
     *
     * <p>调度频率与保留天数解耦：先算截止时间 {@code now - days}，再按
     * {@code start_ts, id} 键集分页扫描全部符合条件的记录（不写库 ⇒ 无法用「已处理标记」翻页）。</p>
     */
//    @Scheduled(cron = "${dims.retention.slice.cron:0 30 3 * * ?}")
    public void cleanupExpiredSliceFiles() {
        if (!running.compareAndSet(false, true)) {
            log.info("上一次切片文件清理任务仍在执行，本次调度跳过");
            return;
        }

        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(10, 3600, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("切片文件清理任务获取分布式锁失败，本次调度跳过");
                return;
            }
            doCleanup();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("切片文件清理任务获取分布式锁被中断", e);
        } catch (Exception e) {
            log.error("切片文件清理任务异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            running.set(false);
        }
    }

    private void doCleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(retentionDays, 1));
        log.info("切片文件清理开始: 保留 {} 天, 清理 start_ts < {} 的切片文件, dryRun={}, batchSize={}",
                retentionDays, cutoff, dryRun, batchSize);

        long scanned = 0;
        long dirsDeleted = 0;
        long objectsDeleted = 0;
        long skipped = 0;
        long failed = 0;

        LocalDateTime lastStartTs = null;
        String lastId = null;

        while (true) {
            List<DataSlice> batch = selectBatch(cutoff, lastStartTs, lastId);
            if (batch.isEmpty()) {
                break;
            }

            for (DataSlice slice : batch) {
                scanned++;
                try {
                    CleanupResult result = cleanupSlice(slice);
                    dirsDeleted += result.dirs();
                    objectsDeleted += result.objects();
                    if (result.dirs() == 0 && result.objects() == 0) {
                        skipped++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("切片文件清理失败: sliceId={}, importSourceDir={}, filePath={}",
                            slice.getId(), slice.getImportSourceDir(), slice.getFilePath(), e);
                }
            }

            DataSlice last = batch.get(batch.size() - 1);
            lastStartTs = last.getStartTs();
            lastId = last.getId();

            if (batch.size() < batchSize) {
                break;
            }
        }

        log.info("切片文件清理结束: 扫描 {} 条, 删除目录 {} 个, 删除对象 {} 个, 无需处理 {} 条, 失败 {} 条, dryRun={}",
                scanned, dirsDeleted, objectsDeleted, skipped, failed, dryRun);
    }

    /**
     * 键集分页查询一批待清理切片（{@code order by start_ts, id} + 行值比较翻页）。
     *
     * <p>只取清理需要的 6 列，避免全字段加载；{@code (start_ts, id) > (?, ?)} 保证
     * start_ts 相同的记录也不会漏读或重复（不写库 ⇒ 不能用 offset 翻页，删完再查会位移）。</p>
     */
    private List<DataSlice> selectBatch(LocalDateTime cutoff, LocalDateTime lastStartTs, String lastId) {
        LambdaQueryWrapper<DataSlice> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(DataSlice::getId, DataSlice::getStartTs, DataSlice::getStatus,
                DataSlice::getStorageType, DataSlice::getFilePath, DataSlice::getImportSourceDir);
        wrapper.lt(DataSlice::getStartTs, cutoff);
        if (lastStartTs != null && lastId != null) {
            // 参数化绑定（{0}/{1} 由 MyBatis-Plus 转为 #{} 占位符），无注入风险
            wrapper.apply("(start_ts, id) > ({0}, {1})", lastStartTs, lastId);
        }
        wrapper.orderByAsc(DataSlice::getStartTs).orderByAsc(DataSlice::getId);
        // batchSize 为 int 配置，直接拼接无注入风险（MP 3.5.9 无分页插件，见 MybatisPlusConfig）
        wrapper.last("LIMIT " + Math.max(batchSize, 1));
        return dataSliceMapper.selectList(wrapper);
    }

    /**
     * 清理单条切片的文件：① import_source_dir 本地目录 → ② file_path 对象存储文件。
     *
     * @return 实际删除的目录数与对象数（dryRun 下为「本应删除」的数量）
     */
    private CleanupResult cleanupSlice(DataSlice slice) {
//        if (slice.getStatus() != null && IN_FLIGHT_STATUSES.contains(slice.getStatus().toUpperCase())) {
//            log.debug("切片处于进行中状态，跳过文件清理: sliceId={}, status={}", slice.getId(), slice.getStatus());
//            return CleanupResult.NONE;
//        }

        int dirs = 0;
        int objects = 0;

        // ① 本地导入源目录（该目录下的全部文件连同目录一起删除）
        String sourceDir = slice.getImportSourceDir();
        if (sourceDir != null && !sourceDir.isBlank()) {
            Path dir = resolveSafeSourceDir(sourceDir);
            if (dir == null) {
                log.warn("import_source_dir 不在允许清理的白名单目录下，跳过本地删除: sliceId={}, dir={}",
                        slice.getId(), sourceDir);
            } else if (!Files.isDirectory(dir)) {
                log.debug("本地源目录已不存在，跳过: sliceId={}, dir={}", slice.getId(), dir);
            } else if (dryRun) {
                log.info("[dryRun] 将删除本地源目录: sliceId={}, dir={}", slice.getId(), dir);
                dirs++;
            } else {
                storageAccess.deleteRecursively(dir);
                log.info("已删除本地源目录: sliceId={}, dir={}", slice.getId(), dir);
                dirs++;
            }
        }

        // ② file_path 指向的对象存储文件
        String filePath = slice.getFilePath();
        if (filePath != null && !filePath.isBlank()) {
            if (storageAccess.isMinioMode() && !"MINIO".equalsIgnoreCase(slice.getStorageType())) {
                // minio 模式下 storage_type=LOCAL 属归档数据（数据在本地，不在 minio），不删对象
                log.debug("minio 模式下归档(LOCAL)记录不删对象存储文件: sliceId={}, filePath={}",
                        slice.getId(), filePath);
            } else if (dryRun) {
                log.info("[dryRun] 将删除存储文件: sliceId={}, filePath={}", slice.getId(), filePath);
                objects++;
            } else {
                storageAccess.delete(filePath);
                log.info("已删除存储文件: sliceId={}, filePath={}", slice.getId(), filePath);
                objects++;
            }
        }

        return new CleanupResult(dirs, objects);
    }

    /**
     * 校验并归一化本地源目录：必须位于白名单根目录之下，且不等于根目录本身。
     *
     * <p>清理（递归删除）与 meta 字段回填（读取 meta 文件）共用同一层校验，
     * 避免脏数据（如 {@code /}、{@code /data}）导致误删整盘或把任意系统路径读出来。</p>
     *
     * @return 归一化后的绝对路径；不在白名单内返回 null
     */
    private Path resolveSafeSourceDir(String sourceDir) {
        Path target;
        try {
            target = Path.of(sourceDir.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            log.warn("import_source_dir 非法，跳过本地删除: {}", sourceDir, e);
            return null;
        }
        for (Path root : allowedRoots()) {
            if (target.startsWith(root) && target.getNameCount() > root.getNameCount()) {
                return target;
            }
        }
        return null;
    }

    private List<Path> allowedRoots() {
        List<Path> roots = new ArrayList<>();
        for (String raw : importSourceRoots.split(",")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                roots.add(Path.of(raw.trim()).toAbsolutePath().normalize());
            } catch (Exception e) {
                log.warn("dims.retention.slice.import-source-roots 配置项非法: {}", raw, e);
            }
        }
        return roots;
    }

    // ========================================================================
    // 附带任务：历史切片 meta 字段回填（event_name / trigger_time）
    // ========================================================================

    /**
     * 回填历史切片的 meta 字段（{@code event_name} / {@code trigger_time}）。
     *
     * <p>背景：早期本地目录导入未从会话目录的 meta 文件解析这两个字段，
     * 导致存量切片 {@code event_name} / {@code trigger_time} 为空 ——
     * 数据浏览器列表的「触发类型 / 触发时间」、MXF 看板事件统计与「数据包总量」的
     * 时间口径（{@code COALESCE(trigger_time, start_ts)}）都会因此缺数据。</p>
     *
     * <p>处理方式：按 {@code id} 键集分页查询「{@code event_name} 或 {@code trigger_time}
     * 为空、且 {@code import_source_dir} 非空」的切片，逐个到 {@code import_source_dir}
     * 下查找 meta 文件（递归查找、文件名不区分大小写，与导入链路
     * {@code LocalDirectoryImportServiceImpl#handleSession} 同一约定），
     * 复用 {@link LocalDirectoryImportServiceImpl#parseMetaJson(String)} 解析出
     * event_name 与触发时间（{@code trigger_timestamp_us + utc_diff_us}，UTC）。
     * <b>只补空值、不覆盖已有值</b>：两个字段都没解析到（或已有值）则不写库。</p>
     *
     * <h3>幂等与安全</h3>
     * <ul>
     *   <li>只填空字段 ⇒ 重复执行不会改写已有值；源目录已清理 / 无 meta 文件的行原样跳过。</li>
     *   <li>源目录必须先通过 {@link #resolveSafeSourceDir(String)} 白名单校验
     *       （{@code dims.retention.slice.import-source-roots}），
     *       防止脏数据把任意系统路径下的 meta 读进来。</li>
     *   <li>与清理任务共用 {@code dims.retention.slice.dry-run}：为 true 时只记日志不写库。</li>
     *   <li>单条失败不影响其余记录：异常按切片粒度捕获并计入失败数。</li>
     * </ul>
     *
     * <p>调度频率由 {@code dims.retention.slice.meta-fixup.cron} 配置
     * （默认每天 04:00；设为 {@code -} 即关闭该任务），单批条数复用
     * {@code dims.retention.slice.batch-size}。</p>
     */
//    @Scheduled(cron = "${dims.retention.slice.meta-fixup.cron:0 0 4 * * ?}")
//    @Scheduled(cron = "0 0/1 * * * ? ")
    public void backfillSliceMetaFields() {
        if (!metaFixupRunning.compareAndSet(false, true)) {
            log.info("上一次切片 meta 字段回填任务仍在执行，本次调度跳过");
            return;
        }

        RLock lock = redissonClient.getLock(META_FIXUP_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(10, 3600, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("切片 meta 字段回填任务获取分布式锁失败，本次调度跳过");
                return;
            }
            doBackfillSliceMetaFields();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("切片 meta 字段回填任务获取分布式锁被中断", e);
        } catch (Exception e) {
            log.error("切片 meta 字段回填任务异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            metaFixupRunning.set(false);
        }
    }

    private void doBackfillSliceMetaFields() {
        log.info("切片 meta 字段回填开始: dryRun={}, batchSize={}", dryRun, batchSize);

        long scanned = 0;
        long eventNamesFilled = 0;
        long triggerTimesFilled = 0;
        long skipped = 0;
        long failed = 0;

        String lastId = null;

        while (true) {
            List<DataSlice> batch = selectMetaFixupBatch(lastId);
            if (batch.isEmpty()) {
                break;
            }

            for (DataSlice slice : batch) {
                scanned++;
                try {
                    MetaFixupResult result = backfillSlice(slice);
                    if (result.eventName()) {
                        eventNamesFilled++;
                    }
                    if (result.triggerTime()) {
                        triggerTimesFilled++;
                    }
                    if (!result.eventName() && !result.triggerTime()) {
                        skipped++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("切片 meta 字段回填失败: sliceId={}, importSourceDir={}",
                            slice.getId(), slice.getImportSourceDir(), e);
                }
            }

            lastId = batch.get(batch.size() - 1).getId();

            if (batch.size() < batchSize) {
                break;
            }
        }

        log.info("切片 meta 字段回填结束: 扫描 {} 条, 回填 event_name {} 条, 回填 trigger_time {} 条, "
                        + "无需回填/未取到值 {} 条, 失败 {} 条, dryRun={}",
                scanned, eventNamesFilled, triggerTimesFilled, skipped, failed, dryRun);
    }

    /**
     * 键集分页查询一批待回填切片（{@code id} 升序 + {@code id > ?} 翻页）。
     *
     * <p>只取回填需要的 4 列；{@code import_source_dir} 为空的行永远无法回填，
     * 由 SQL 直接排除，避免每次调度空扫。回填过的行不再命中过滤条件
     * （{@code id} 只增不减），因此 {@code id} 键集翻页不会漏读或重复。</p>
     */
    private List<DataSlice> selectMetaFixupBatch(String lastId) {
        LambdaQueryWrapper<DataSlice> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(DataSlice::getId, DataSlice::getEventName, DataSlice::getTriggerTime,
                DataSlice::getImportSourceDir);
        // event_name 或 trigger_time 为空即候选（空串同样算空；纯空白由 isBlank 在 Java 侧兜住）
        wrapper.and(w -> w.isNull(DataSlice::getEventName)
                .or().eq(DataSlice::getEventName, "")
                .or().isNull(DataSlice::getTriggerTime));
        wrapper.isNotNull(DataSlice::getImportSourceDir).ne(DataSlice::getImportSourceDir, "");
        if (lastId != null) {
            wrapper.gt(DataSlice::getId, lastId);
        }
        wrapper.orderByAsc(DataSlice::getId);
        // batchSize 为 int 配置，直接拼接无注入风险（MP 3.5.9 无分页插件，见 MybatisPlusConfig）
        wrapper.last("LIMIT " + Math.max(batchSize, 1));
        return dataSliceMapper.selectList(wrapper);
    }

    /**
     * 回填单条切片的 meta 字段：读源目录下的 meta 文件 → 解析 event_name / 触发时间
     * → 仅把缺失字段组装成局部更新（{@code patch} 只带 id + 需要补的字段，
     * MP 默认忽略 null 字段，不会覆盖库中已有值）。
     *
     * @return 实际补齐的字段（dryRun 下为「本应补齐」的字段）
     */
    private MetaFixupResult backfillSlice(DataSlice slice) {
        Path dir = resolveSafeSourceDir(slice.getImportSourceDir());
        if (dir == null) {
            log.warn("import_source_dir 不在允许回填的白名单目录下，跳过 meta 回填: sliceId={}, dir={}",
                    slice.getId(), slice.getImportSourceDir());
            return MetaFixupResult.NONE;
        }
        if (!Files.isDirectory(dir)) {
            log.debug("本地源目录已不存在，跳过 meta 回填: sliceId={}, dir={}", slice.getId(), dir);
            return MetaFixupResult.NONE;
        }

        LocalDirectoryImportServiceImpl.MetaInfo meta = readMetaInfo(dir);
        if (meta == null) {
            log.debug("源目录下没有可解析的 meta 文件，跳过 meta 回填: sliceId={}, dir={}", slice.getId(), dir);
            return MetaFixupResult.NONE;
        }

        DataSlice patch = new DataSlice();
        patch.setId(slice.getId());
        boolean eventNameFilled = false;
        boolean triggerTimeFilled = false;
        // 只补缺失字段：已有值不覆盖（event_name 为 null/空串/纯空白均视为缺失）
        if (isBlank(slice.getEventName()) && !isBlank(meta.getEventName())) {
            patch.setEventName(meta.getEventName());
            eventNameFilled = true;
        }
        if (slice.getTriggerTime() == null && meta.getTriggerTimeUtc() != null) {
            patch.setTriggerTime(meta.getTriggerTimeUtc());
            triggerTimeFilled = true;
        }
        if (!eventNameFilled && !triggerTimeFilled) {
            return MetaFixupResult.NONE;
        }

        if (dryRun) {
            log.info("[dryRun] 将回填切片 meta 字段: sliceId={}, eventName={}, triggerTime={}",
                    slice.getId(), patch.getEventName(), patch.getTriggerTime());
            return new MetaFixupResult(eventNameFilled, triggerTimeFilled);
        }

        dataSliceMapper.updateById(patch);
        log.info("已回填切片 meta 字段: sliceId={}, eventName={}, triggerTime={}",
                slice.getId(), patch.getEventName(), patch.getTriggerTime());
        return new MetaFixupResult(eventNameFilled, triggerTimeFilled);
    }

    /**
     * 在会话目录下查找并解析 meta 文件。
     *
     * <p>递归查找 + 文件名不区分大小写，与导入链路
     * {@code LocalDirectoryImportServiceImpl#handleSession} 的查找约定一致，
     * 避免「导入能找到、回填找不到」的口径差异。</p>
     *
     * @return 解析结果；文件不存在 / 目录不可遍历 / 读取或解析失败均为 null（调用方按跳过处理）
     */
    private LocalDirectoryImportServiceImpl.MetaInfo readMetaInfo(Path sessionDir) {
        Path metaFile;
        try (Stream<Path> stream = Files.walk(sessionDir)) {
            Optional<Path> found = stream.filter(Files::isRegularFile)
                    .filter(p -> META_FILE_NAME.equalsIgnoreCase(p.getFileName().toString()))
                    .findFirst();
            if (found.isEmpty()) {
                return null;
            }
            metaFile = found.get();
        } catch (IOException e) {
            log.warn("遍历源目录查找 meta 文件失败: dir={}, err={}", sessionDir, e.getMessage());
            return null;
        }

        try {
            return LocalDirectoryImportServiceImpl.parseMetaJson(Files.readString(metaFile));
        } catch (IOException e) {
            log.warn("读取 meta 文件失败: file={}, err={}", metaFile, e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 单条切片的 meta 字段回填结果
     */
    private record MetaFixupResult(boolean eventName, boolean triggerTime) {
        static final MetaFixupResult NONE = new MetaFixupResult(false, false);
    }

    /**
     * 单条切片的清理结果
     */
    private record CleanupResult(int dirs, int objects) {
        static final CleanupResult NONE = new CleanupResult(0, 0);
    }
}
