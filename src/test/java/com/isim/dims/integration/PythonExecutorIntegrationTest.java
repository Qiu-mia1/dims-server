package com.isim.dims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.util.PythonExecutor;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Integration test for PythonExecutor using real ROS1 bag files.
 * <p>
 * These tests require:
 *   1. python3 on PATH
 *   2. The rosbags Python package installed
 *   3. Access to the test bag files in /home/nnn/project/JMC/isim-dims/data/
 * <p>
 * If any prerequisite is missing, tests are skipped (not failed).
 */
@Tag("integration")
class PythonExecutorIntegrationTest {

    private static final String BAG_FILE_1 =
            "/home/nnn/project/JMC/isim-dims/data/1784265036517857_1784263960761964_30_5f61f3ca4a3bf76d22a808ede0f16b61.bag";
    private static final String BAG_FILE_2 =
            "/home/nnn/project/JMC/isim-dims/data/mapping_input.bag";

    private static boolean prerequisitesMet = false;
    private static String skipReason = "";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PythonExecutor executor;

    @BeforeAll
    static void checkPrerequisites() {
        try {
            // Check python3
            Process checkPython = new ProcessBuilder("python3", "--version")
                    .start();
            boolean pythonOk = checkPython.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                    && checkPython.exitValue() == 0;
            if (!pythonOk) {
                skipReason = "python3 not available on PATH";
                return;
            }

            // Check rosbags module
            Process checkRosbags = new ProcessBuilder("python3", "-c", "import rosbags; print('ok')")
                    .start();
            boolean rosbagsOk = checkRosbags.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                    && checkRosbags.exitValue() == 0;
            if (!rosbagsOk) {
                skipReason = "rosbags Python package not installed";
                return;
            }

            // Check bag files exist
            java.nio.file.Path bag1 = java.nio.file.Paths.get(BAG_FILE_1);
            java.nio.file.Path bag2 = java.nio.file.Paths.get(BAG_FILE_2);
            if (!java.nio.file.Files.exists(bag1) || !java.nio.file.Files.exists(bag2)) {
                skipReason = "Test bag files not found (expected at " + BAG_FILE_1 + " and " + BAG_FILE_2 + ")";
                return;
            }

            prerequisitesMet = true;
        } catch (Exception e) {
            skipReason = "Prerequisite check failed: " + e.getMessage();
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(prerequisitesMet, skipReason);

        executor = new PythonExecutor();
        ReflectionTestUtils.setField(executor, "scriptDir", "../dims-python");
        ReflectionTestUtils.setField(executor, "defaultTimeoutSec", 300);
    }

    // ============ parse_rosbag.py ============

    @Test
    void parseRosbag_WithBagFile1_ReturnsValidMetadata() throws Exception {
        String result = executor.execute("parse_rosbag.py",
                Map.of("bag_path", BAG_FILE_1), 120);

        JsonNode json = objectMapper.readTree(result);

        // Verify top-level fields
        assertTrue(json.has("start_ts"), "Missing start_ts");
        assertTrue(json.has("end_ts"), "Missing end_ts");
        assertTrue(json.has("duration_ms"), "Missing duration_ms");
        assertTrue(json.has("msg_count_total"), "Missing msg_count_total");
        assertTrue(json.has("streams"), "Missing streams");

        // Verify types and values
        assertTrue(json.get("duration_ms").asLong() > 0, "duration_ms should be positive");
        assertTrue(json.get("msg_count_total").asLong() > 0, "msg_count_total should be positive");

        // Verify streams
        JsonNode streams = json.get("streams");
        assertTrue(streams.isArray() && streams.size() > 0, "streams should be a non-empty array");

        // Check first stream structure
        JsonNode firstStream = streams.get(0);
        assertTrue(firstStream.has("topic"), "Stream missing topic");
        assertTrue(firstStream.has("msg_type"), "Stream missing msg_type");
        assertTrue(firstStream.has("sensor_category"), "Stream missing sensor_category");
        assertTrue(firstStream.has("msg_count"), "Stream missing msg_count");
        assertTrue(firstStream.has("frequency_hz"), "Stream missing frequency_hz");
        assertTrue(firstStream.has("first_ts"), "Stream missing first_ts");
        assertTrue(firstStream.has("last_ts"), "Stream missing last_ts");

        // Verify at least one message in each stream
        assertTrue(firstStream.get("msg_count").asInt() > 0, "Stream msg_count should be > 0");

        // gps_bbox can be null for this bag
        assertTrue(json.has("gps_bbox"), "Missing gps_bbox field");

        System.out.println("Bag1 parse result: " + json.get("msg_count_total") + " messages, "
                + streams.size() + " streams, duration=" + json.get("duration_ms") + "ms");
    }

    @Test
    void parseRosbag_WithBagFile2_ReturnsValidMetadata() throws Exception {
        String result = executor.execute("parse_rosbag.py",
                Map.of("bag_path", BAG_FILE_2), 120);

        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.has("start_ts"));
        assertTrue(json.has("end_ts"));
        assertTrue(json.get("duration_ms").asLong() > 0);
        assertTrue(json.get("msg_count_total").asLong() > 0);

        JsonNode streams = json.get("streams");
        assertTrue(streams.isArray() && streams.size() > 0);

        // Verify all streams have required fields
        for (JsonNode stream : streams) {
            assertTrue(stream.has("topic"));
            assertTrue(stream.has("sensor_category"));
            assertTrue(stream.has("msg_count"));
            assertTrue(stream.get("msg_count").asInt() > 0);
        }

        System.out.println("Bag2 parse result: " + json.get("msg_count_total") + " messages, "
                + streams.size() + " streams");
    }

    @Test
    void parseRosbag_WithBagFile1_StreamsHaveValidCategories() throws Exception {
        String result = executor.execute("parse_rosbag.py",
                Map.of("bag_path", BAG_FILE_1), 120);

        JsonNode json = objectMapper.readTree(result);
        JsonNode streams = json.get("streams");

        // Verify each stream has a valid sensor_category
        for (JsonNode stream : streams) {
            String category = stream.get("sensor_category").asText();
            assertTrue(List.of("camera", "lidar", "radar", "gnss_imu",
                    "vehicle_signal", "ultrasonic", "adas", "other").contains(category),
                    "Unexpected sensor_category: " + category);
        }
    }

    @Test
    void parseRosbag_InvalidPath_ThrowsException() {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> executor.execute("parse_rosbag.py",
                        Map.of("bag_path", "/nonexistent/bag.bag"), 30));
        assertTrue(exception.getMessage().contains("exit code 1") ||
                        exception.getMessage().contains("Error"),
                "Expected error message about missing file, got: " + exception.getMessage());
    }

    // ============ auto_tag.py ============

    @Test
    void autoTag_DaytimeWithFullSensor_ReturnsExpectedTags() throws Exception {
        // 昼夜判定按采集地时区（默认 UTC+8，见 auto_tag.py _apply_time_rule）：
        // 04:00Z = 北京正午 12:00 → daytime
        String result = executor.execute("auto_tag.py",
                Map.of(
                        "slice_id", "test-slice-1",
                        "start_ts", "2026-07-24T04:00:00Z",
                        "end_ts", "2026-07-24T04:30:00Z",
                        "sensor_types", List.of("camera", "radar"),
                        "gps_bbox", Map.of(
                                "min_lat", 31.2, "min_lon", 121.4,
                                "max_lat", 31.3, "max_lon", 121.5
                        )
                ), 30);

        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.has("tag_ids"), "Missing tag_ids");
        assertTrue(json.get("tag_ids").isArray(), "tag_ids should be an array");

        // daytime (tag-004) + full-sensor (tag-010)
        assertTrue(json.get("tag_ids").toString().contains("tag-004"),
                "Expected tag-004 (daytime)");
        assertTrue(json.get("tag_ids").toString().contains("tag-010"),
                "Expected tag-010 (full-sensor)");
    }

    @Test
    void autoTag_NightWithPartialSensor_ReturnsExpectedTags() throws Exception {
        // 昼夜判定按采集地时区（默认 UTC+8，见 auto_tag.py _apply_time_rule）：
        // 14:00Z = 北京 22:00 → night
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("slice_id", "test-slice-2");
        args.put("start_ts", "2026-07-24T14:00:00Z");
        args.put("end_ts", "2026-07-24T14:30:00Z");
        args.put("sensor_types", java.util.List.of("lidar"));
        // gps_bbox deliberately omitted (null handled by script)

        String result = executor.execute("auto_tag.py", args, 30);

        JsonNode json = objectMapper.readTree(result);

        // night (tag-005) + partial (tag-011)
        assertTrue(json.get("tag_ids").toString().contains("tag-005"),
                "Expected tag-005 (night)");
        assertTrue(json.get("tag_ids").toString().contains("tag-011"),
                "Expected tag-011 (partial)");
    }

    @Test
    void autoTag_MissingSliceId_ThrowsException() {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> executor.execute("auto_tag.py",
                        Map.of("start_ts", "2026-07-24T10:00:00Z"), 30));
        assertTrue(exception.getMessage().contains("exit code 1") ||
                        exception.getMessage().contains("Error"),
                "Expected error about missing slice_id");
    }

    // ============ Timeout handling ============

    @Test
    void executor_ShortTimeout_Throws() {
        // Use a very short timeout (1 second) — the Python scripts should take longer to parse
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> executor.execute("parse_rosbag.py",
                        Map.of("bag_path", BAG_FILE_1), 1));
        assertTrue(exception.getMessage().contains("timed out"),
                "Expected timeout exception, got: " + exception.getMessage());
    }
}
