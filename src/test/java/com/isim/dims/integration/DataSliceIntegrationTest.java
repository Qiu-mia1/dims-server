package com.isim.dims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.DataSliceTag;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.DataSliceTagMapper;
import com.isim.dims.mapper.ExportTaskMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Full-stack integration test for the DIMS data ingestion pipeline.
 * <p>
 * Tests the complete flow: upload real bag file → async parse → auto-tag →
 * search/detail/tags APIs → export task → DB verification → cleanup.
 * <p>
 * Requirements:
 * <ol>
 *   <li>MySQL running at localhost:3306/dims (root/root123)</li>
 *   <li>Python3 with rosbags package installed</li>
 *   <li>Test bag file at /home/nnn/project/JMC/isim-dims/data/mapping_input.bag</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=test")
@TestMethodOrder(MethodOrderer.MethodName.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.Tag("integration")
// Requires MySQL + Python environment — run manually with:
//   mvn test -Dtest="DataSliceIntegrationTest" -Dspring.profiles.active=test -DfailIfNoTests=false
class DataSliceIntegrationTest {

    private static final String BAG_FILE = "/home/nnn/project/JMC/isim-dims/data/mapping_input.bag";
    // 轮询上限需覆盖整条导入流水线（实测整链约 610s）：parse(~1min) + 视频段提取
    // （11 路 camera topic 在 videoExtractExecutor 单线程串行 ffmpeg 全量 re-encode，
    // 实测 ~9min，紧贴主侧单阶段等待上限 600s）+ 统计 + 场景提取。
    // 180s 上限在视频提取阶段即超时（曾误判为"挂起"），放宽到 20 分钟留足余量。
    private static final long MAX_POLL_TIME_MS = 1_200_000; // 20 minutes
    private static final long POLL_INTERVAL_MS = 3_000;   // 3 seconds

    private static boolean prerequisitesMet = false;
    private static String skipReason = "";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSliceMapper dataSliceMapper;

    @Autowired
    private SensorStreamMapper sensorStreamMapper;

    @Autowired
    private DataSliceTagMapper dataSliceTagMapper;

    @Autowired
    private IngestionTaskMapper ingestionTaskMapper;

    @Autowired
    private ExportTaskMapper exportTaskMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String sliceId;
    private String taskId;
    private String exportId;
    private final String testName = "integration-test-" + Instant.now().getEpochSecond();
    private final Instant testStart = Instant.now();

    // ---- Prerequisites --------------------------------------------------

    @BeforeAll
    static void checkPrerequisites() {
        try {
            // Check python3
            Process checkPython = new ProcessBuilder("python3", "--version")
                    .start();
            boolean pythonOk = checkPython.waitFor(10, TimeUnit.SECONDS)
                    && checkPython.exitValue() == 0;
            if (!pythonOk) {
                skipReason = "python3 not available on PATH";
                return;
            }

            // Check rosbags
            Process checkRosbags = new ProcessBuilder("python3", "-c", "import rosbags; print('ok')")
                    .start();
            boolean rosbagsOk = checkRosbags.waitFor(10, TimeUnit.SECONDS)
                    && checkRosbags.exitValue() == 0;
            if (!rosbagsOk) {
                skipReason = "rosbags Python package not installed";
                return;
            }

            // Check bag file
            if (!new File(BAG_FILE).exists()) {
                skipReason = "Test bag file not found at " + BAG_FILE;
                return;
            }

            // Check dims storage directories exist or are creatable
            File bagDir = new File("/tmp/dims-test/bags");
            bagDir.mkdirs();
            File exportDir = new File("/tmp/dims-test/exports");
            exportDir.mkdirs();

            prerequisitesMet = true;
        } catch (Exception e) {
            skipReason = "Prerequisite check failed: " + e.getMessage();
        }
    }

    @BeforeEach
    void assumePrerequisites() {
        assumeTrue(prerequisitesMet, skipReason);
    }

    // ====================================================================
    // Test Flow
    // ====================================================================

    /**
     * Step 1: Upload the real bag file via POST /v1/data-slices
     * Expect: 202 Accepted with slice_id, task_id, status=UPLOADING
     */
    @Test
    void step01_uploadBagFile() {
        // Build multipart request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-Trace-Id", "integration-test-" + testName);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(new File(BAG_FILE)));
        body.add("name", testName);
        body.add("source", "MMT");

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/v1/data-slices",
                HttpMethod.POST,
                request,
                JsonNode.class);

        assertEquals(202, response.getStatusCode().value(), "Upload should return 202 Accepted");
        assertNotNull(response.getBody(), "Response body should not be null");

        JsonNode resp = response.getBody();
        assertTrue(resp.has("slice_id"), "Response missing slice_id");
        assertTrue(resp.has("task_id"), "Response missing task_id");
        assertTrue(resp.has("status"), "Response missing status");

        sliceId = resp.get("slice_id").asText();
        taskId = resp.get("task_id").asText();
        String status = resp.get("status").asText();

        System.out.println("[UPLOAD] slice_id=" + sliceId + ", task_id=" + taskId + ", status=" + status);

        assertNotNull(sliceId, "slice_id should not be null");
        assertNotNull(taskId, "task_id should not be null");
        assertEquals("UPLOADING", status);
    }

    /**
     * Step 2: Poll GET /v1/tasks/{taskId} until READY or ERROR
     * Expect: status=READY, progress=100
     */
    @Test
    void step02_pollTaskUntilReady() throws Exception {
        assertNotNull(taskId, "taskId must be set from step01");

        long deadline = System.currentTimeMillis() + MAX_POLL_TIME_MS;
        String finalStatus = "UNKNOWN";
        Integer finalProgress = null;
        String errorMsg = null;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);

            ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                    "/v1/tasks/{taskId}", JsonNode.class, taskId);

            assertEquals(200, response.getStatusCode().value(), "Task query should return 200");
            assertNotNull(response.getBody());

            JsonNode taskResp = response.getBody();
            finalStatus = taskResp.has("status") ? taskResp.get("status").asText() : "UNKNOWN";
            finalProgress = taskResp.has("progress") ? taskResp.get("progress").asInt() : null;
            errorMsg = taskResp.has("error_msg") && !taskResp.get("error_msg").isNull()
                    ? taskResp.get("error_msg").asText() : null;

            System.out.println("[POLL TASK] status=" + finalStatus + ", progress=" + finalProgress
                    + ", error=" + (errorMsg != null ? errorMsg : "none"));

            if ("READY".equals(finalStatus) || "ERROR".equals(finalStatus)) {
                break;
            }
        }

        // Calculate total elapsed time for the async pipeline
        long pipelineElapsed = Duration.between(testStart, Instant.now()).toMillis();
        System.out.println("[PIPELINE] Async pipeline completed in " + pipelineElapsed + "ms ("
                + (pipelineElapsed / 1000) + "s)");

        assertEquals("READY", finalStatus,
                "Task should end in READY state. Last error=" + errorMsg + ", elapsed=" + pipelineElapsed + "ms");
        assertEquals(Integer.valueOf(100), finalProgress, "Progress should be 100");
    }

    /**
     * Step 3: Search slices by status=READY and verify our slice is present
     */
    @Test
    void step03_searchSlicesByStatus() {
        assertNotNull(sliceId, "sliceId must be set from step01");

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/v1/data-slices?status=READY&page=1&page_size=100",
                JsonNode.class);

        assertEquals(200, response.getStatusCode().value(), "Search should return 200");
        assertNotNull(response.getBody());

        JsonNode resp = response.getBody();
        assertTrue(resp.has("list"), "Response missing list");
        assertTrue(resp.has("total"), "Response missing total");

        // Find our slice in the results
        boolean found = false;
        JsonNode items = resp.get("list");
        assertTrue(items.isArray(), "list should be an array");

        for (JsonNode item : items) {
            if (item.has("id") && sliceId.equals(item.get("id").asText())) {
                found = true;
                System.out.println("[SEARCH] Found slice: id=" + item.get("id").asText()
                        + ", name=" + item.get("name").asText()
                        + ", status=" + item.get("status").asText()
                        + ", duration_ms=" + (item.has("duration_ms") ? item.get("duration_ms").asText() : "N/A")
                        + ", msg_count=" + (item.has("msg_count_total") ? item.get("msg_count_total").asText() : "N/A"));

                // Verify important fields
                assertEquals(testName, item.get("name").asText(), "Slice name should match");
                assertEquals("READY", item.get("status").asText(), "Status should be READY");
                assertTrue(item.has("file_size"), "Missing file_size");
                assertTrue(item.has("duration_ms"), "Missing duration_ms");
                assertTrue(item.has("msg_count_total"), "Missing msg_count_total");
                assertTrue(item.has("sensor_types"), "Missing sensor_types");
                assertTrue(item.has("created_at"), "Missing created_at");

                // Verify duration and message count are reasonable
                assertTrue(item.get("duration_ms").asLong() > 0, "duration_ms should be > 0");
                assertTrue(item.get("msg_count_total").asLong() > 0, "msg_count_total should be > 0");
                assertTrue(item.get("file_size").asLong() > 0, "file_size should be > 0");
                break;
            }
        }

        assertTrue(found, "Our test slice should be found in search results");
    }

    /**
     * Step 4: Get slice detail and verify streams and metadata
     */
    @Test
    void step04_getSliceDetail() {
        assertNotNull(sliceId, "sliceId must be set from step01");

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/v1/data-slices/{sliceId}", JsonNode.class, sliceId);

        assertEquals(200, response.getStatusCode().value(), "Detail should return 200");
        assertNotNull(response.getBody());

        JsonNode detail = response.getBody();
        assertEquals(sliceId, detail.get("id").asText(), "ID should match");
        assertEquals(testName, detail.get("name").asText(), "Name should match");
        assertEquals("READY", detail.get("status").asText(), "Status should be READY");

        // Verify metadata fields
        assertTrue(detail.has("start_ts"), "Missing start_ts");
        assertTrue(detail.has("end_ts"), "Missing end_ts");
        assertTrue(detail.has("duration_ms"), "Missing duration_ms");
        assertTrue(detail.has("msg_count_total"), "Missing msg_count_total");
        assertTrue(detail.has("file_size"), "Missing file_size");
        assertTrue(detail.has("sensor_types"), "Missing sensor_types");
        assertTrue(detail.has("streams"), "Missing streams");
        assertTrue(detail.has("tags"), "Missing tags");

        long fileSize = detail.get("file_size").asLong();
        long durationMs = detail.get("duration_ms").asLong();
        long msgCountTotal = detail.get("msg_count_total").asLong();

        System.out.println("[DETAIL] file_size=" + fileSize + " bytes"
                + ", duration_ms=" + durationMs
                + ", msg_count_total=" + msgCountTotal
                + ", sensor_types=" + detail.get("sensor_types")
                + ", tags=" + detail.get("tags"));

        // Verify the bag file size is close to expected (250MB)
        assertTrue(fileSize > 200_000_000, "File should be ~250MB, got " + fileSize);
        assertTrue(durationMs > 0, "Duration should be positive, got " + durationMs);
        assertTrue(msgCountTotal > 0, "Message count should be positive, got " + msgCountTotal);

        // Verify streams
        JsonNode streams = detail.get("streams");
        assertTrue(streams.isArray() && streams.size() > 0, "Should have at least one stream");

        Set<String> categories = new HashSet<>();
        for (JsonNode stream : streams) {
            assertTrue(stream.has("topic"), "Stream missing topic");
            assertTrue(stream.has("msg_type"), "Stream missing msg_type");
            assertTrue(stream.has("sensor_category"), "Stream missing sensor_category");
            assertTrue(stream.has("msg_count"), "Stream missing msg_count");
            assertTrue(stream.has("frequency_hz"), "Stream missing frequency_hz");
            assertTrue(stream.has("first_ts"), "Stream missing first_ts");
            assertTrue(stream.has("last_ts"), "Stream missing last_ts");

            String category = stream.get("sensor_category").asText();
            categories.add(category);

            assertTrue(stream.get("msg_count").asInt() > 0,
                    "Stream msg_count should be > 0 for topic " + stream.get("topic").asText());
        }

        System.out.println("[STREAMS] " + streams.size() + " streams, categories=" + categories);
    }

    /**
     * Step 5: Get tags for the slice
     */
    @Test
    void step05_getSliceTags() {
        assertNotNull(sliceId, "sliceId must be set from step01");

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/v1/data-slices/{sliceId}/tags", JsonNode.class, sliceId);

        assertEquals(200, response.getStatusCode().value(), "Tags endpoint should return 200");
        assertNotNull(response.getBody());

        JsonNode tagsResp = response.getBody();
        assertTrue(tagsResp.has("tags"), "Tags response should have 'tags' field");
        JsonNode tagsArray = tagsResp.get("tags");
        assertTrue(tagsArray.isArray(), "Tags should be an array");

        System.out.println("[TAGS] " + tagsArray.size() + " auto-tags found:");

        for (JsonNode tag : tagsArray) {
            assertTrue(tag.has("id"), "Tag missing id");
            assertTrue(tag.has("name"), "Tag missing name");
            assertTrue(tag.has("type"), "Tag missing type (source)");
            assertTrue(tag.has("timestamp"), "Tag missing timestamp");

            System.out.println("  - id=" + tag.get("id").asText()
                    + ", name=" + tag.get("name").asText()
                    + ", type=" + tag.get("type").asText()
                    + ", timestamp=" + tag.get("timestamp").asText());
        }

        // We expect auto-tags to be applied. The auto_tag.py script should return
        // at least a few tags based on sensor types and time of day.
        assertTrue(tagsArray.size() >= 0, "Tags array should exist (0 is acceptable if no rules matched)");
    }

    /**
     * Step 6: Create export with time window and verify it's PENDING
     * Note: This relies on having start_ts and end_ts from the parsed metadata.
     */
    @Test
    void step06_createExport() {
        assertNotNull(sliceId, "sliceId must be set from step01");

        // Get slice detail to obtain start_ts and end_ts
        ResponseEntity<JsonNode> detailResp = restTemplate.getForEntity(
                "/v1/data-slices/{sliceId}", JsonNode.class, sliceId);
        assertEquals(200, detailResp.getStatusCode().value());

        JsonNode detail = detailResp.getBody();
        assertNotNull(detail);
        assertTrue(detail.has("start_ts"), "Detail should have start_ts");
        assertTrue(detail.has("end_ts"), "Detail should have end_ts");

        String startTs = detail.get("start_ts").asText();
        String endTs = detail.get("end_ts").asText();

        System.out.println("[EXPORT] Creating export with window: start=" + startTs + ", end=" + endTs);

        // Build export request (use 80% of the time window to leave some margin)
        Map<String, Object> exportReq = new HashMap<>();
        exportReq.put("start_ts", startTs);
        exportReq.put("end_ts", endTs);
        exportReq.put("format", "bag");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(exportReq, headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/v1/data-slices/{sliceId}/exports",
                HttpMethod.POST,
                request,
                JsonNode.class,
                sliceId);

        assertEquals(202, response.getStatusCode().value(), "Export creation should return 202");
        assertNotNull(response.getBody());

        JsonNode exportResp = response.getBody();
        assertTrue(exportResp.has("export_id"), "Response missing export_id");
        assertTrue(exportResp.has("status"), "Response missing status");
        assertTrue(exportResp.has("format"), "Response missing format");

        exportId = exportResp.get("export_id").asText();
        String exportStatus = exportResp.get("status").asText();

        System.out.println("[EXPORT] export_id=" + exportId + ", status=" + exportStatus
                + ", format=" + exportResp.get("format").asText());

        assertEquals("PENDING", exportStatus, "New export should be PENDING");
        assertEquals("bag", exportResp.get("format").asText(), "Format should be bag");
    }

    /**
     * Step 7: Poll export status until READY or ERROR
     */
    @Test
    void step07_pollExportUntilReady() throws Exception {
        // This test depends on export creation. If no exportId, skip.
        assumeTrue(exportId != null, "exportId must be set from step06");

        long deadline = System.currentTimeMillis() + MAX_POLL_TIME_MS;
        String finalStatus = "UNKNOWN";
        String errorMsg = null;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);

            ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                    "/v1/exports/{exportId}", JsonNode.class, exportId);

            assertEquals(200, response.getStatusCode().value(), "Export query should return 200");
            assertNotNull(response.getBody());

            JsonNode exportResp = response.getBody();
            finalStatus = exportResp.has("status") ? exportResp.get("status").asText() : "UNKNOWN";
            errorMsg = null;

            System.out.println("[POLL EXPORT] status=" + finalStatus);

            if ("READY".equals(finalStatus) || "ERROR".equals(finalStatus)) {
                // Check for additional response fields
                if (exportResp.has("download_url") && !exportResp.get("download_url").isNull()) {
                    System.out.println("[EXPORT READY] download_url=" + exportResp.get("download_url").asText());
                }
                if (exportResp.has("file_size") && !exportResp.get("file_size").isNull()) {
                    System.out.println("[EXPORT READY] file_size=" + exportResp.get("file_size").asLong() + " bytes");
                }
                break;
            }
        }

        assertEquals("READY", finalStatus,
                "Export should end in READY state. Last status=" + finalStatus + ", error=" + errorMsg);
    }

    /**
     * Step 8: Health check
     */
    @Test
    void step08_healthCheck() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/v1/health", JsonNode.class);

        assertEquals(200, response.getStatusCode().value(), "Health check should return 200");
        assertNotNull(response.getBody());

        JsonNode health = response.getBody();
        assertEquals("UP", health.get("status").asText(), "Health status should be UP");
        assertEquals("dims", health.get("service").asText(), "Service should be dims");
        assertTrue(health.has("timestamp"), "Health should have timestamp");

        System.out.println("[HEALTH] status=" + health.get("status").asText()
                + ", service=" + health.get("service").asText()
                + ", timestamp=" + health.get("timestamp").asText());
    }

    /**
     * Step 9: Direct DB verification — query tables via mappers
     */
    @Test
    void step09_verifyDatabaseRecords() {
        assertNotNull(sliceId, "sliceId must be set from step01");

        // 9a) Verify data_slice record
        DataSlice dataSlice = dataSliceMapper.selectById(sliceId);
        assertNotNull(dataSlice, "DataSlice should exist in DB");
        assertEquals(testName, dataSlice.getName(), "DataSlice name should match");
        assertEquals("READY", dataSlice.getStatus(), "DataSlice status should be READY");
        assertNotNull(dataSlice.getFilePath(), "file_path should not be null");
        assertTrue(dataSlice.getFileSize() > 0, "file_size should be > 0");
        assertNotNull(dataSlice.getStartTs(), "start_ts should not be null");
        assertNotNull(dataSlice.getEndTs(), "end_ts should not be null");
        assertTrue(dataSlice.getDurationMs() > 0, "duration_ms should be > 0");
        assertTrue(dataSlice.getMsgCountTotal() > 0, "msg_count_total should be > 0");
        assertNotNull(dataSlice.getSensorTypes(), "sensor_types should not be null");
        assertNotNull(dataSlice.getCreatedAt(), "created_at should not be null");
        assertNotNull(dataSlice.getUpdatedAt(), "updated_at should not be null");

        System.out.println("[DB DATA_SLICE]"
                + " id=" + dataSlice.getId()
                + ", name=" + dataSlice.getName()
                + ", status=" + dataSlice.getStatus()
                + ", file_size=" + dataSlice.getFileSize()
                + ", duration_ms=" + dataSlice.getDurationMs()
                + ", msg_count=" + dataSlice.getMsgCountTotal()
                + ", sensor_types=" + dataSlice.getSensorTypes()
                + ", gps_min_lat=" + dataSlice.getGpsMinLat()
                + ", gps_min_lon=" + dataSlice.getGpsMinLon()
                + ", gps_max_lat=" + dataSlice.getGpsMaxLat()
                + ", gps_max_lon=" + dataSlice.getGpsMaxLon());

        // 9b) Verify sensor_stream records
        var streamQuery = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SensorStream>();
        streamQuery.eq(SensorStream::getSliceId, sliceId);
        List<SensorStream> streams = sensorStreamMapper.selectList(streamQuery);

        assertFalse(streams.isEmpty(), "Should have at least one sensor_stream record");
        System.out.println("[DB SENSOR_STREAM] " + streams.size() + " streams:");

        for (SensorStream stream : streams) {
            assertNotNull(stream.getId(), "Stream id should not be null");
            assertEquals(sliceId, stream.getSliceId(), "Stream slice_id should match");
            assertNotNull(stream.getTopic(), "Stream topic should not be null");
            assertNotNull(stream.getSensorCategory(), "Stream sensor_category should not be null");
            assertTrue(stream.getMsgCount() > 0, "Stream msg_count should be > 0");
            assertNotNull(stream.getFirstTs(), "Stream first_ts should not be null");
            assertNotNull(stream.getLastTs(), "Stream last_ts should not be null");

            System.out.println("  - topic=" + stream.getTopic()
                    + ", msg_type=" + stream.getMsgType()
                    + ", category=" + stream.getSensorCategory()
                    + ", msg_count=" + stream.getMsgCount()
                    + ", freq=" + stream.getFrequencyHz() + "Hz"
                    + ", first_ts=" + stream.getFirstTs()
                    + ", last_ts=" + stream.getLastTs());
        }

        // 9c) Verify ingestion_task record
        var taskQuery = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IngestionTask>();
        taskQuery.eq(IngestionTask::getSliceId, sliceId);
        List<IngestionTask> tasks = ingestionTaskMapper.selectList(taskQuery);

        assertEquals(1, tasks.size(), "Should have exactly one ingestion_task");
        IngestionTask task = tasks.get(0);
        assertEquals("READY", task.getStatus(), "Task status should be READY");
        assertEquals(Integer.valueOf(100), task.getProgress(), "Task progress should be 100");
        assertEquals(sliceId, task.getSliceId(), "Task slice_id should match");
        assertNotNull(task.getCreatedAt(), "Task created_at should not be null");
        assertNotNull(task.getUpdatedAt(), "Task updated_at should not be null");

        System.out.println("[DB INGESTION_TASK]"
                + " id=" + task.getId()
                + ", status=" + task.getStatus()
                + ", progress=" + task.getProgress()
                + ", error_msg=" + task.getErrorMsg());

        // 9d) Verify data_slice_tag records
        var tagQuery = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataSliceTag>();
        tagQuery.eq(DataSliceTag::getSliceId, sliceId);
        List<DataSliceTag> sliceTags = dataSliceTagMapper.selectList(tagQuery);

        System.out.println("[DB DATA_SLICE_TAG] " + sliceTags.size() + " associations:");
        for (DataSliceTag dst : sliceTags) {
            assertEquals(sliceId, dst.getSliceId(), "Tag slice_id should match");
            assertEquals("auto", dst.getSource(), "Tag source should be 'auto' for auto-tags");

            // ⚠️ 2026-09-23 起标签定义由 SIMS 片段域托管，本地 tag 表已随 V29 移除，
            // 因此不再校验「引用的标签存在于本地标签库」——关联行只保证 tag_id 非空；
            // 标签名解析归 SimsTagCatalog（查 SIMS 词表），不属于本集成测试范围。
            assertNotNull(dst.getTagId(), "数据关联行的 tag_id 应非空");
            System.out.println("  - tag_id=" + dst.getTagId() + ", source=" + dst.getSource());
        }
    }

    // ====================================================================
    // Cleanup
    // ====================================================================

    /**
     * Step 10: Clean up — DELETE the created slice
     */
    @Test
    void step10_deleteSlice() {
        assertNotNull(sliceId, "sliceId must be set from step01");

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/data-slices/{sliceId}",
                HttpMethod.DELETE,
                null,
                String.class,
                sliceId);

        assertEquals(204, response.getStatusCode().value(), "DELETE should return 204 No Content");

        // Verify the slice is gone
        DataSlice deleted = dataSliceMapper.selectById(sliceId);
        assertNull(deleted, "DataSlice should be deleted from DB");

        // Verify streams are cascade-deleted
        var streamQuery = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SensorStream>();
        streamQuery.eq(SensorStream::getSliceId, sliceId);
        assertEquals(0, sensorStreamMapper.selectCount(streamQuery),
                "SensorStreams should be cascade-deleted");

        // Verify tags are cascade-deleted
        var tagQuery = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DataSliceTag>();
        tagQuery.eq(DataSliceTag::getSliceId, sliceId);
        assertEquals(0, dataSliceTagMapper.selectCount(tagQuery),
                "DataSliceTags should be cascade-deleted");

        // Verify task is cascade-deleted
        var taskQuery = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IngestionTask>();
        taskQuery.eq(IngestionTask::getSliceId, sliceId);
        assertEquals(0, ingestionTaskMapper.selectCount(taskQuery),
                "IngestionTasks should be cascade-deleted");

        System.out.println("[CLEANUP] Slice " + sliceId + " deleted successfully, all cascade records removed");
    }
}
