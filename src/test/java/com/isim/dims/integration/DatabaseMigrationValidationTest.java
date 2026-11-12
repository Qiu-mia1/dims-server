package com.isim.dims.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据库迁移文件 V2__phase2_new_tables.sql 验证测试
 *
 * 验证 SQL 语法正确性（通过检查关键结构）。
 * 注意：此测试不连接实际数据库，仅做静态分析。
 */
class DatabaseMigrationValidationTest {

    private static final String MIGRATION_PATH =
            "/home/nnn/project/JMC/isim-dims/dims-server/src/main/resources/db/migration/V2__phase2_new_tables.sql";

    private static final String V6_MIGRATION_PATH =
            "/home/nnn/project/JMC/isim-dims/dims-server/src/main/resources/db/migration/V6__trajectory_check_marker.sql";

    // ============ SQL 文件存在及结构验证 ============

    @Test
    @DisplayName("V2 迁移文件存在且可读")
    void migrationFile_ExistsAndReadable() {
        Path sqlFile = Paths.get(MIGRATION_PATH);
        assertTrue(Files.exists(sqlFile), "V2 migration file should exist");
        assertTrue(Files.isReadable(sqlFile), "V2 migration file should be readable");
    }

    @Test
    @DisplayName("V2 迁移文件创建了 gps_trajectory 表")
    void migrationFile_CreatesGpsTrajectoryTable() throws IOException {
        String sql = Files.readString(Paths.get(MIGRATION_PATH));

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS gps_trajectory"),
                "Should create gps_trajectory table");
    }

    @Test
    @DisplayName("gps_trajectory 表包含必要的列和索引")
    void gpsTrajectoryTable_HasRequiredColumns() throws IOException {
        String tableSql = readTableSql("gps_trajectory");
        String fileSql = Files.readString(Paths.get(MIGRATION_PATH));

        assertAll("gps_trajectory structure",
                () -> assertTrue(tableSql.contains("id"), "Should have id column"),
                () -> assertTrue(tableSql.contains("slice_id"), "Should have slice_id column"),
                () -> assertTrue(tableSql.contains("lat"), "Should have lat column"),
                () -> assertTrue(tableSql.contains("lon"), "Should have lon column"),
                // PG 方言：表内 KEY 外置为独立 CREATE INDEX，且 PG 索引名 schema 全局唯一，加表前缀
                () -> assertTrue(fileSql.contains("CREATE INDEX idx_gps_trajectory_slice_id ON gps_trajectory (slice_id)"),
                        "Should have slice_id index"),
                () -> assertTrue(fileSql.contains("CREATE INDEX idx_ts ON gps_trajectory (ts)"),
                        "Should have ts index"),
                () -> assertTrue(tableSql.contains("FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE"),
                        "Should have FK to data_slice with ON DELETE CASCADE")
        );
    }

    @Test
    @DisplayName("gps_trajectory 的 slice_id 外键使用 CASCADE 删除")
    void gpsTrajectory_HasCascadeDelete() throws IOException {
        String sql = readTableSql("gps_trajectory");

        assertTrue(sql.contains("ON DELETE CASCADE"),
                "gps_trajectory FK should use ON DELETE CASCADE");
    }

    // ============ V6: 轨迹提取结果标记迁移验证 ============

    @Test
    @DisplayName("V6 迁移文件存在且可读")
    void v6MigrationFile_ExistsAndReadable() {
        Path sqlFile = Paths.get(V6_MIGRATION_PATH);
        assertTrue(Files.exists(sqlFile), "V6 migration file should exist");
        assertTrue(Files.isReadable(sqlFile), "V6 migration file should be readable");
    }

    @Test
    @DisplayName("V6 迁移为 data_slice 增加 trajectory_status 和 trajectory_checked_at 列")
    void v6Migration_AddsTrajectoryMarkerColumns() throws IOException {
        String sql = Files.readString(Paths.get(V6_MIGRATION_PATH));

        assertTrue(sql.contains("ALTER TABLE data_slice"), "Should ALTER data_slice table");
        assertTrue(sql.contains("trajectory_status"), "Should add trajectory_status column");
        assertTrue(sql.contains("trajectory_checked_at"), "Should add trajectory_checked_at column");
    }

    // ============ 辅助方法 ============

    /**
     * 从迁移 SQL 中提取指定表的 CREATE TABLE 语句
     */
    private String readTableSql(String tableName) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(MIGRATION_PATH));
        StringBuilder tableSql = new StringBuilder();
        boolean inTable = false;

        for (String line : lines) {
            if (line.contains("CREATE TABLE IF NOT EXISTS " + tableName)) {
                inTable = true;
            }
            if (inTable) {
                tableSql.append(line).append("\n");
                if (line.trim().endsWith(");")) {
                    break;
                }
            }
        }

        assertThat(tableSql.toString())
                .as("CREATE TABLE statement for " + tableName + " should be found")
                .isNotEmpty();
        return tableSql.toString();
    }
}
