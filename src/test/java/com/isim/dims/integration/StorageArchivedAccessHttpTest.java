package com.isim.dims.integration;

import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.SceneExtractionTask;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.SceneExtractionTaskMapper;
import com.isim.dims.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 归档切片拦截 HTTP 层测试（MinIO 迁移）
 *
 * <p>minio 模式 + storage_type=LOCAL 的存量切片 → 数据内容读取/写入类端点
 * 必须返回 <b>410 DATA_ARCHIVED</b>（契约裁决 2）。本测试走真实链路：
 * Controller → 真实 Service → 真实 {@code StorageAccess.assertNotArchived}
 * （storageConfig.type=minio 生效），仅 {@link StorageService} 用 mock 替代
 * （内存存储，归档拦截在触达存储之前抛错，无需真实 MinIO）。</p>
 *
 * <p>三个入口的映射机制不同，逐一覆盖：</p>
 * <ul>
 *   <li>ExportController.createExport —— 契约预期经 @ExceptionHandler(StorageArchivedException) → 410；
 *       <b>注意：当前实现 createExport 内部 catch(Exception) 吞掉 StorageArchivedException 返回
 *       500 EXPORT_FAILED（与 downloadExport 的 @ExceptionHandler 路径不同），
 *       本用例按契约断言 410，若实现存在缺口将如实暴露。</b></li>
 *   <li>VisualizationController.extractVideoSegment / extractSignalData —— 类型化 catch（generic 之前）→ 410</li>
 *   <li>SceneExtractionController.readProcessFile —— 消息前缀匹配 "DATA_ARCHIVED:" → 410</li>
 * </ul>
 *
 * <p>运行前提：localhost:5432/dims PG（profile=test，DDL 自动执行）；
 * 测试数据按 id 唯一命名并在 @AfterEach 清理（scene_extraction_task 受
 * data_slice 外键 ON DELETE CASCADE 级联删除）。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "dims.storage.type=minio",
        "minio.endpoint=http://localhost:9000",
        "minio.access-key=minioadmin",
        "minio.secret-key=minioadmin",
        "minio.bucket=dims-test"
})
@AutoConfigureMockMvc
class StorageArchivedAccessHttpTest {

    /** 归档切片（storage_type=LOCAL）——createExport 入口 */
    private static final String SLICE_EXPORT = "arch-slice-export";
    /** 归档切片 —— extractVideoSegment 入口 */
    private static final String SLICE_VIDEO = "arch-slice-video";
    /** 归档切片 —— extractSignalData 入口 */
    private static final String SLICE_SIGNAL = "arch-slice-signal";
    /** 归档切片 —— readProcessFile 入口 */
    private static final String SLICE_SCENE = "arch-slice-scene";

    private static final String SCENE_OUTPUT =
            "/data/bags/scene_data/" + SLICE_SCENE + "/detected_scenes.json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSliceMapper dataSliceMapper;

    @Autowired
    private SceneExtractionTaskMapper sceneExtractionTaskMapper;

    /** 内存存储：归档拦截在触达存储前抛错，真实 MinIO 不参与 */
    @MockBean
    private StorageService storageService;

    @BeforeEach
    void seedArchivedSlices() {
        insertSlice(SLICE_EXPORT);
        insertSlice(SLICE_VIDEO);
        insertSlice(SLICE_SIGNAL);
        insertSlice(SLICE_SCENE);
        insertSceneTask();
    }

    @AfterEach
    void cleanup() {
        // scene_extraction_task 外键 ON DELETE CASCADE，删切片即可
        for (String id : List.of(SLICE_EXPORT, SLICE_VIDEO, SLICE_SIGNAL, SLICE_SCENE)) {
            dataSliceMapper.deleteById(id);
        }
    }

    private void insertSlice(String id) {
        DataSlice slice = new DataSlice();
        slice.setId(id);
        slice.setName("archived-" + id);
        slice.setFilePath("/tmp/dims-test/bags/" + id + "/original.bag");
        slice.setStorageType("LOCAL");
        slice.setStatus("READY");
        slice.setSourceType("BAG");
        slice.setFileSize(1024L);
        dataSliceMapper.insert(slice);
    }

    private void insertSceneTask() {
        SceneExtractionTask task = new SceneExtractionTask();
        task.setId(java.util.UUID.randomUUID().toString());
        task.setSliceId(SLICE_SCENE);
        task.setStatus("SUCCESS");
        task.setProgress(100);
        task.setOutputPath(SCENE_OUTPUT);
        sceneExtractionTaskMapper.insert(task);
    }

    // ============ ExportController.createExport ============

    /**
     * 契约：归档切片发起导出 → 410 DATA_ARCHIVED。
     * 当前实现 createExport 内部 catch(Exception) 会吞掉 StorageArchivedException
     * （@ExceptionHandler 仅对 downloadExport 生效），若本用例失败即为真实缺口。
     */
    @Test
    void createExport_ArchivedSlice_Returns410DataArchived() throws Exception {
        mockMvc.perform(post("/v1/data-slices/{sliceId}/exports", SLICE_EXPORT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start_ts\":\"2026-07-24T09:00:00Z\","
                                + "\"end_ts\":\"2026-07-24T09:30:00Z\",\"format\":\"bag\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("DATA_ARCHIVED"));
    }

    // ============ VisualizationController.extractVideoSegment ============

    @Test
    void extractVideoSegment_ArchivedSlice_Returns410DataArchived() throws Exception {
        mockMvc.perform(post("/v1/data-slices/{sliceId}/video-streams/extract", SLICE_VIDEO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topics\":[\"/sensor/camera_front/video\"],"
                                + "\"start_ts\":\"2026-07-24T09:00:00Z\","
                                + "\"end_ts\":\"2026-07-24T09:30:00Z\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("DATA_ARCHIVED"));
    }

    // ============ VisualizationController.extractSignalData ============

    @Test
    void extractSignalData_ArchivedSlice_Returns410DataArchived() throws Exception {
        mockMvc.perform(post("/v1/data-slices/{sliceId}/signal-data", SLICE_SIGNAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topics\":[\"/vehicle/chassis/speed\"],"
                                + "\"start_ts\":\"2026-07-24T09:00:00Z\","
                                + "\"end_ts\":\"2026-07-24T09:30:00Z\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("DATA_ARCHIVED"));
    }

    // ============ SceneExtractionController.readProcessFile ============

    @Test
    void readProcessFile_ArchivedSlice_Returns410DataArchived() throws Exception {
        mockMvc.perform(get("/v1/data-slices/{sliceId}/scene-extraction/files/content",
                        SLICE_SCENE)
                        .param("path", SCENE_OUTPUT))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("DATA_ARCHIVED"));
    }
}
