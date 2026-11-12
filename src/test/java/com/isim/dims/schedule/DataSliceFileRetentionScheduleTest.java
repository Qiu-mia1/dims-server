package com.isim.dims.schedule;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.impl.StorageAccess;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史切片 meta 字段回填（{@link DataSliceFileRetentionSchedule#backfillSliceMetaFields()}）单元测试。
 *
 * <p>真实临时目录 + mock mapper：覆盖 meta 文件查找（含子目录）、解析与触发时间换算、
 * 「只补空值不覆盖已有值」的更新语义，以及白名单 / dryRun 两条安全约束。
 * 解析实现本身复用 {@code LocalDirectoryImportServiceImpl#parseMetaJson(String)}，
 * 故这里同时校验导入链路与回填链路的字段口径一致。</p>
 *
 * @feature: infra
 */
@ExtendWith(MockitoExtension.class)
class DataSliceFileRetentionScheduleTest {

    /** 触发时间换算基准：1600000000s = 2020-09-13T12:26:40Z */
    private static final long TRIGGER_TS_US = 1_600_000_000_000_000L;

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private StorageAccess storageAccess;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    /** 白名单根目录（dims.retention.slice.import-source-roots） */
    @TempDir
    Path tempDir;

    /** 白名单之外的兄弟目录：存在且含 meta，回填仍必须被拒绝 */
    @TempDir
    Path outsideDir;

    private DataSliceFileRetentionSchedule schedule;

    /**
     * {@code LambdaQueryWrapper} 把 {@code SFunction} 解析成列名依赖 MyBatis-Plus 的
     * TableInfo 缓存（由 MyBatis 容器初始化）。纯 Mockito 单测没有容器，
     * 不预热会在构建 wrapper 时抛
     * {@code MybatisPlusException: can not find lambda cache for this entity}，
     * 导致回填任务在发起查询前就异常退出 —— 故此处在测试启动时手动初始化实体元信息。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataSlice.class);
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        schedule = new DataSliceFileRetentionSchedule(dataSliceMapper, storageAccess, redissonClient);
        ReflectionTestUtils.setField(schedule, "batchSize", 200);
        ReflectionTestUtils.setField(schedule, "dryRun", false);
        ReflectionTestUtils.setField(schedule, "retentionDays", 30);
        ReflectionTestUtils.setField(schedule, "importSourceRoots", tempDir.toString());

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void backfillSliceMetaFields_FillsEventNameAndTriggerTimeFromMeta() throws IOException {
        Path sessionDir = createSession("4bfb23eb_20260709143844460937_public");
        writeMeta(sessionDir, "{\"car_type\":\"CX835ICA\",\"collect_type\":\"CFDI\","
                + "\"event_name\":\"fushion_aeb\",\"trigger_timestamp_us\":" + TRIGGER_TS_US
                + ",\"utc_diff_us\":3600000000}");

        stubCandidates(candidate("slice-1", sessionDir));

        schedule.backfillSliceMetaFields();

        DataSlice patch = capturedPatch();
        assertThat(patch.getId()).isEqualTo("slice-1");
        assertThat(patch.getEventName()).isEqualTo("fushion_aeb");
        // trigger_timestamp_us(12:26:40Z) + utc_diff_us(1h) = 13:26:40Z，按 UTC 落库
        assertThat(patch.getTriggerTime()).isEqualTo(LocalDateTime.of(2020, 9, 13, 13, 26, 40));
    }

    @Test
    void backfillSliceMetaFields_KeepsExistingEventName_FillsTriggerTimeOnly() throws IOException {
        Path sessionDir = createSession("4bfb23eb_20260709143844460937_public");
        writeMeta(sessionDir, "{\"event_name\":\"imu_collision\","
                + "\"trigger_timestamp_us\":" + TRIGGER_TS_US + ",\"utc_diff_us\":0}");

        DataSlice existing = candidate("slice-2", sessionDir);
        existing.setEventName("system_crash"); // 库中已有值：meta 不得覆盖
        stubCandidates(existing);

        schedule.backfillSliceMetaFields();

        DataSlice patch = capturedPatch();
        assertThat(patch.getEventName()).isNull();
        assertThat(patch.getTriggerTime()).isEqualTo(LocalDateTime.of(2020, 9, 13, 12, 26, 40));
    }

    @Test
    void backfillSliceMetaFields_BlankEventNameTreatedAsMissing() throws IOException {
        Path sessionDir = createSession("4bfb23eb_20260709143844460937_public");
        // meta 无 trigger_timestamp_us / utc_diff_us → 触发时间保持为空，只补 event_name
        writeMeta(sessionDir, "{\"event_name\":\"disabled\"}");

        DataSlice blank = candidate("slice-3", sessionDir);
        blank.setEventName("   "); // 纯空白同样视为缺失
        stubCandidates(blank);

        schedule.backfillSliceMetaFields();

        DataSlice patch = capturedPatch();
        assertThat(patch.getEventName()).isEqualTo("disabled");
        assertThat(patch.getTriggerTime()).isNull();
    }

    @Test
    void backfillSliceMetaFields_FindsMetaInSubDirectory() throws IOException {
        Path sessionDir = createSession("4bfb23eb_20260709143844460937_public");
        Path nested = Files.createDirectories(sessionDir.resolve("help").resolve("index"));
        writeMeta(nested, "{\"event_name\":\"unp_tkov_colli\","
                + "\"trigger_timestamp_us\":" + TRIGGER_TS_US + ",\"utc_diff_us\":0}");

        stubCandidates(candidate("slice-4", sessionDir));

        schedule.backfillSliceMetaFields();

        DataSlice patch = capturedPatch();
        assertThat(patch.getEventName()).isEqualTo("unp_tkov_colli");
        assertThat(patch.getTriggerTime()).isEqualTo(LocalDateTime.of(2020, 9, 13, 12, 26, 40));
    }

    @Test
    void backfillSliceMetaFields_SourceDirOutsideWhitelist_Skips() throws IOException {
        assertThat(outsideDir.startsWith(tempDir)).isFalse();
        writeMeta(outsideDir, "{\"event_name\":\"fushion_aeb\"}");

        stubCandidates(candidate("slice-5", outsideDir));

        schedule.backfillSliceMetaFields();

        verify(dataSliceMapper, never()).updateById(any(DataSlice.class));
    }

    @Test
    void backfillSliceMetaFields_NoMetaFile_Skips() throws IOException {
        Path sessionDir = createSession("4bfb23eb_20260709143844460937_public");
        Files.writeString(sessionDir.resolve("upload.done"), "done");

        stubCandidates(candidate("slice-6", sessionDir));

        schedule.backfillSliceMetaFields();

        verify(dataSliceMapper, never()).updateById(any(DataSlice.class));
    }

    @Test
    void backfillSliceMetaFields_UnparsableMeta_Skips() throws IOException {
        Path sessionDir = createSession("4bfb23eb_20260709143844460937_public");
        writeMeta(sessionDir, "not-a-json");

        stubCandidates(candidate("slice-7", sessionDir));

        schedule.backfillSliceMetaFields();

        verify(dataSliceMapper, never()).updateById(any(DataSlice.class));
    }

    @Test
    void backfillSliceMetaFields_MetaWithoutTargetFields_Skips() throws IOException {
        Path sessionDir = createSession("4bfb23eb_20260709143844460937_public");
        writeMeta(sessionDir, "{\"car_type\":\"CX835ICA\",\"collect_type\":\"CFDI\"}");

        stubCandidates(candidate("slice-8", sessionDir));

        schedule.backfillSliceMetaFields();

        verify(dataSliceMapper, never()).updateById(any(DataSlice.class));
    }

    @Test
    void backfillSliceMetaFields_DryRun_DoesNotWrite() throws IOException {
        ReflectionTestUtils.setField(schedule, "dryRun", true);
        Path sessionDir = createSession("4bfb23eb_20260709143844460937_public");
        writeMeta(sessionDir, "{\"event_name\":\"fushion_aeb\"}");

        stubCandidates(candidate("slice-9", sessionDir));

        schedule.backfillSliceMetaFields();

        // dryRun 只统计：候选行被识别但不写库
        verify(dataSliceMapper, never()).updateById(any(DataSlice.class));
    }

    // -----------------------------------------------------------------------

    private Path createSession(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private static void writeMeta(Path dir, String content) throws IOException {
        Files.writeString(dir.resolve("meta"), content);
    }

    private static DataSlice candidate(String id, Path sourceDir) {
        DataSlice slice = new DataSlice();
        slice.setId(id);
        slice.setImportSourceDir(sourceDir.toAbsolutePath().toString());
        return slice;
    }

    /**
     * 候选行只查一批（batchSize=200 > 1），处理完即结束，无需 stub 第二轮空批。
     */
    private void stubCandidates(DataSlice... slices) {
        when(dataSliceMapper.selectList(any())).thenReturn(List.of(slices));
    }

    private DataSlice capturedPatch() {
        ArgumentCaptor<DataSlice> captor = ArgumentCaptor.forClass(DataSlice.class);
        verify(dataSliceMapper).updateById(captor.capture());
        return captor.getValue();
    }
}
