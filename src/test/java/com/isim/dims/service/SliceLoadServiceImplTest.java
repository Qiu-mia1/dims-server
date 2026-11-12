package com.isim.dims.service;

import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.entity.IngestionTaskStatusHistory;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.IngestionTaskStatusHistoryMapper;
import com.isim.dims.service.impl.SliceLoadServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SliceLoadServiceImpl} 单元测试（按需加载：统计 → LOADING → 全量拉取 → 复用导入流水线）
 *
 * <p>异步下载线程由 {@code downloadPrefix} 闸门控制：默认阻塞在闸门前，
 * 保证同步断言不受异步推进干扰；需要验证「下载完成后派发流水线」时在用例内放行。</p>
 */
@ExtendWith(MockitoExtension.class)
class SliceLoadServiceImplTest {

    private static final String SLICE_ID = "slice-1";

    private static final String BOS_KEY = "mmt_test/20260902/abc.../abc..._20260902000000_public";

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private IngestionTaskMapper ingestionTaskMapper;

    @Mock
    private IngestionTaskStatusHistoryMapper ingestionTaskStatusHistoryMapper;

    @Mock
    private IngestionService ingestionService;

    @Mock
    private LocalDirectoryImportPipelineService localDirectoryImportPipelineService;

    @Mock
    private BosDirectoryRestoreService bosDirectoryRestoreService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @InjectMocks
    private SliceLoadServiceImpl sliceLoadService;

    /** 放开后异步下载才继续（默认在 setUp 创建，测试末尾放开） */
    private CountDownLatch downloadGate;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(sliceLoadService, "downloadBytesPerSecond", 10 * 1024 * 1024L); // 10MB/s
        ReflectionTestUtils.setField(sliceLoadService, "pipelineSeconds", 600L);
        ReflectionTestUtils.setField(sliceLoadService, "minEstimatedSeconds", 60L);
        ReflectionTestUtils.setField(sliceLoadService, "concurrency", 1);

        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(rLock.isHeldByCurrentThread()).thenReturn(false);

        downloadGate = new CountDownLatch(1);
        lenient().when(bosDirectoryRestoreService.downloadPrefix(anyString(), any())).thenAnswer(inv -> {
            downloadGate.await(5, TimeUnit.SECONDS);
            return 0;
        });
    }

    @AfterEach
    void tearDown() {
        // 放行可能被阻塞的异步加载线程，避免其泄漏到后续用例
        downloadGate.countDown();
    }

    private DataSlice metaReadySlice() {
        DataSlice slice = new DataSlice();
        slice.setId(SLICE_ID);
        slice.setStatus("META_READY");
        slice.setImportBosKey(BOS_KEY);
        slice.setImportSourceDir("/data/downloads/" + BOS_KEY);
        slice.setImportMd5("abcabcabcabcabcabcabcabcabcabc1");
        slice.setSessionId("abc1_20260902000000_public");
        slice.setVin("LXWHEKDC7THG05069");
        slice.setFileSize(100L);
        return slice;
    }

    // ============ startLoad ============

    @Test
    void startLoad_metaReady_marksLoadingAndReturnsEstimate() {
        DataSlice slice = metaReadySlice();
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(slice);
        when(ingestionTaskMapper.selectOne(any())).thenReturn(null); // 无任务 → 新建
        when(ingestionTaskMapper.insert(any(IngestionTask.class))).thenReturn(1);
        when(ingestionTaskStatusHistoryMapper.insert(any(IngestionTaskStatusHistory.class))).thenReturn(1);
        // 200MB / 10MB/s = 20s 下载 + 600s 流水线 = 620s
        when(bosDirectoryRestoreService.statPrefix(BOS_KEY))
                .thenReturn(new BosDirectoryRestoreService.PrefixStat(200 * 1024 * 1024L, 50, 0L));

        Map<String, Object> resp = sliceLoadService.startLoad(SLICE_ID);

        assertThat(resp)
                .containsEntry("slice_id", SLICE_ID)
                .containsEntry("status", "LOADING");
        assertThat((Long) resp.get("estimated_seconds")).isEqualTo(620L);
        assertThat((Long) resp.get("total_bytes")).isEqualTo(200 * 1024 * 1024L);

        // 切片进入 LOADING（异步下载线程仍阻塞在闸门前，不会推进到 UPLOADING）
//        verify(dataSliceMapper).updateById(argThat(s -> "LOADING".equals(s.getStatus())));
        ArgumentCaptor<IngestionTask> taskCaptor = ArgumentCaptor.forClass(IngestionTask.class);
        verify(ingestionTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("LOADING");
    }

    @Test
    void startLoad_afterPreviousFailure_allowsRetry() {
        DataSlice slice = metaReadySlice();
        slice.setStatus("ERROR");
        slice.setErrorMsg("LOAD_DOWNLOAD_FAILED: boom");
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(slice);
        // 已有任务（首次导入/上次失败遗留）→ 复用而不是新建
        IngestionTask existing = new IngestionTask();
        existing.setId("task-1");
        existing.setSliceId(SLICE_ID);
        existing.setStatus("ERROR");
        existing.setProgress(0);
        when(ingestionTaskMapper.selectOne(any())).thenReturn(existing);
        when(bosDirectoryRestoreService.statPrefix(BOS_KEY))
                .thenReturn(new BosDirectoryRestoreService.PrefixStat(1024L, 2, 1024L));

        Map<String, Object> resp = sliceLoadService.startLoad(SLICE_ID);

        assertThat(resp).containsEntry("status", "LOADING").containsEntry("task_id", "task-1");
        // 本地已有全部字节 → 仍需下载 0 字节，预计 = 下限 60s + 流水线 600s
        assertThat((Long) resp.get("estimated_seconds")).isEqualTo(660L);
        verify(ingestionService).updateTaskStatus(argThat(t -> "task-1".equals(t.getId())),
                eq("LOADING"), eq(0));
        verify(ingestionTaskMapper, org.mockito.Mockito.never()).insert(any(IngestionTask.class));
    }

    @Test
    void startLoad_sliceNotFound_throws() {
        when(dataSliceMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> sliceLoadService.startLoad("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SLICE_NOT_FOUND");
    }

    @Test
    void startLoad_withoutBosKey_throwsNotSupported() {
        DataSlice slice = metaReadySlice();
        slice.setImportBosKey(null);
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(slice);

        assertThatThrownBy(() -> sliceLoadService.startLoad(SLICE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOAD_NOT_SUPPORTED");
    }

    @Test
    void startLoad_alreadyLoaded_throwsInvalidState() {
        DataSlice slice = metaReadySlice();
        slice.setStatus("READY");
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(slice);

        assertThatThrownBy(() -> sliceLoadService.startLoad(SLICE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOAD_STATE_INVALID");
    }

    @Test
    void startLoad_alreadyLoading_throwsInProgress() {
        DataSlice slice = metaReadySlice();
        slice.setStatus("LOADING");
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(slice);

        assertThatThrownBy(() -> sliceLoadService.startLoad(SLICE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOAD_IN_PROGRESS");
    }

    @Test
    void startLoad_emptyPrefixObjects_throwsSourceEmpty() {
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(metaReadySlice());
        when(bosDirectoryRestoreService.statPrefix(BOS_KEY))
                .thenReturn(new BosDirectoryRestoreService.PrefixStat(0L, 0, 0L));

        assertThatThrownBy(() -> sliceLoadService.startLoad(SLICE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOAD_SOURCE_EMPTY");
    }

    @Test
    void startLoad_afterDownload_dispatchesImportPipeline() throws Exception {
        DataSlice slice = metaReadySlice();
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(slice);
        when(ingestionTaskMapper.selectOne(any())).thenReturn(null);
        when(ingestionTaskMapper.insert(any(IngestionTask.class))).thenReturn(1);
        when(ingestionTaskStatusHistoryMapper.insert(any(IngestionTaskStatusHistory.class))).thenReturn(1);
        IngestionTask created = new IngestionTask();
        created.setId("task-2");
        created.setSliceId(SLICE_ID);
        when(ingestionTaskMapper.selectById(anyString())).thenReturn(created);
        when(bosDirectoryRestoreService.statPrefix(BOS_KEY))
                .thenReturn(new BosDirectoryRestoreService.PrefixStat(1024L, 2, 0L));

        sliceLoadService.startLoad(SLICE_ID);
        downloadGate.countDown();

        // 下载完成 → 切片回 UPLOADING 并派发本地目录导入流水线（复用 importDirectory 的转换链路）
        verify(bosDirectoryRestoreService, timeout(3000)).downloadPrefix(eq(BOS_KEY), any());
        verify(dataSliceMapper, timeout(3000)).updateById(argThat((DataSlice s) -> "UPLOADING".equals(s.getStatus())));
        verify(localDirectoryImportPipelineService, timeout(3000)).processOneImport(any());
    }

    // ============ getLoadStatus ============

    @Test
    void getLoadStatus_metaReady_returnsNotStarted() {
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(metaReadySlice());
        when(ingestionTaskMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> resp = sliceLoadService.getLoadStatus(SLICE_ID);

        assertThat(resp)
                .containsEntry("slice_id", SLICE_ID)
                .containsEntry("status", "META_READY")
                .containsEntry("stage", "NOT_STARTED")
                .containsEntry("progress", 0)
                .containsEntry("remaining_seconds", 0L);
    }

    @Test
    void getLoadStatus_pipelinePhase_mapsProgressInto20To100() {
        DataSlice slice = metaReadySlice();
        slice.setStatus("CONVERTING");
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(slice);
        IngestionTask task = new IngestionTask();
        task.setId("task-1");
        task.setSliceId(SLICE_ID);
        task.setStatus("CONVERTING");
        task.setProgress(50);
        when(ingestionTaskMapper.selectOne(any())).thenReturn(task);

        Map<String, Object> resp = sliceLoadService.getLoadStatus(SLICE_ID);

        assertThat(resp).containsEntry("stage", "PROCESSING").containsEntry("status", "CONVERTING");
        // 20 + 50 * 80 / 100 = 60
        assertThat(resp.get("progress")).isEqualTo(60);
        // 流水线剩余 600 * 50% = 300s
        assertThat(resp.get("remaining_seconds")).isEqualTo(300L);
    }

    @Test
    void getLoadStatus_ready_returnsDoneAndClearsProgress() {
        DataSlice slice = metaReadySlice();
        slice.setStatus("READY");
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(slice);
        when(ingestionTaskMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> resp = sliceLoadService.getLoadStatus(SLICE_ID);

        assertThat(resp).containsEntry("stage", "DONE").containsEntry("progress", 100)
                .containsEntry("remaining_seconds", 0L);
    }

    @Test
    void getLoadStatus_error_returnsFailedWithReason() {
        DataSlice slice = metaReadySlice();
        slice.setStatus("ERROR");
        slice.setErrorMsg("LOAD_DOWNLOAD_FAILED: key -> path");
        when(dataSliceMapper.selectById(SLICE_ID)).thenReturn(slice);
        when(ingestionTaskMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> resp = sliceLoadService.getLoadStatus(SLICE_ID);

        assertThat(resp)
                .containsEntry("stage", "FAILED")
                .containsEntry("error_msg", "LOAD_DOWNLOAD_FAILED: key -> path")
                .containsEntry("remaining_seconds", 0L);
    }

    @Test
    void getLoadStatus_sliceNotFound_throws() {
        when(dataSliceMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> sliceLoadService.getLoadStatus("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SLICE_NOT_FOUND");
    }
}
