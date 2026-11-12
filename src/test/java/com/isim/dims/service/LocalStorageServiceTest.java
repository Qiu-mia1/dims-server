package com.isim.dims.service;

import com.isim.dims.config.StorageConfig;
import com.isim.dims.exception.StorageException;
import com.isim.dims.exception.StorageNotFoundException;
import com.isim.dims.service.impl.LocalStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LocalStorageService 存储层防线测试（Review S1 第二层 + W5 行为）
 *
 * <p>绝对路径 key 的数据根目录包含性校验（归一化 + toRealPath 双层），
 * 与 readProcessFile 校验层（SceneExtractionPipelineServiceImplTest）构成纵深防御。
 * 存量绝对路径 file_path（位于数据根目录内）不受影响。</p>
 */
class LocalStorageServiceTest {

    @TempDir
    Path tempDir;

    private StorageConfig config;
    private LocalStorageService storage;

    @BeforeEach
    void setUp() {
        config = new StorageConfig();
        config.setBagDir(tempDir.resolve("bags").toString());
        config.setExportDir(tempDir.resolve("exports").toString());
        config.setVideoDir(tempDir.resolve("videos").toString());
        config.setSignalDir(tempDir.resolve("signals").toString());
        config.setReplayCacheDir(tempDir.resolve("cache").toString());
        config.setCfdiDir(tempDir.resolve("cfdi").toString());
        storage = new LocalStorageService(config);
    }

    private String bagDir() {
        return tempDir.resolve("bags").toAbsolutePath().normalize().toString();
    }

    // ============ S1 第二层：绝对路径根目录包含性 ============

    @Test
    void resolveLocalPath_LegacyAbsoluteInsideRoot_Allowed() {
        String legacy = bagDir() + "/slice-1/original.bag";
        Path resolved = storage.resolveLocalPath(legacy);
        assertThat(resolved.toString()).isEqualTo(legacy);
    }

    @Test
    void resolveLocalPath_AbsoluteOutsideRoot_Rejected() {
        assertThatThrownBy(() -> storage.resolveLocalPath("/etc/passwd"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("STORAGE_INVALID_KEY");
    }

    @Test
    void resolveLocalPath_AbsoluteDotDotEscape_Rejected() {
        // 命中根目录前缀但 "../.." 归一化逃逸 → 拒绝
        String attack = bagDir() + "/slice-1/../../../../etc/passwd";
        assertThatThrownBy(() -> storage.resolveLocalPath(attack))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("STORAGE_INVALID_KEY");
    }

    @Test
    void resolveLocalPath_RelativeKey_Ok() {
        Path resolved = storage.resolveLocalPath("slices/slice-1/original.bag");
        assertThat(resolved.toString())
                .isEqualTo(Path.of(bagDir(), "slice-1", "original.bag").toString());
    }

    @Test
    void resolveLocalPath_RelativeDotDot_Rejected() {
        assertThatThrownBy(() -> storage.resolveLocalPath("slices/../../etc/passwd"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("STORAGE_INVALID_KEY");
    }

    // ============ 读取路径：真实路径包含性（symlink 逃逸防御） ============

    @Test
    void open_LegacyAbsoluteFile_ReadsContent() throws IOException {
        Path file = Path.of(bagDir(), "slice-1", "original.bag");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello-bag");

        try (InputStream in = storage.open(file.toString())) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo("hello-bag");
        }
    }

    @Test
    void open_AbsoluteSymlinkEscapingRoot_Rejected() throws IOException {
        // bag 目录内 symlink 指向根目录外文件 → toRealPath 逃逸 → StorageNotFoundException
        Path link = Path.of(bagDir(), "slice-1", "escape.txt");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, Path.of("/etc/hostname"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "symlink not supported, skipping");
            return;
        }
        assertThatThrownBy(() -> storage.open(link.toString()))
                .isInstanceOf(StorageNotFoundException.class);
    }

    // ============ W5：deleteByPrefix 实际行为（删内容并删目录本身） ============

    @Test
    void deleteByPrefix_DeletesContentsAndDirectoryItself() throws IOException {
        Path dir = Path.of(bagDir(), "slice-9");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("segment.jsonl"), "{}");
        Files.writeString(dir.resolve("nested.txt"), "x");

        storage.deleteByPrefix("slices/slice-9");

        assertThat(Files.exists(dir)).isFalse();
        assertThat(Files.exists(dir.resolve("segment.jsonl"))).isFalse();
    }

    @Test
    void deleteByPrefix_AbsoluteOutsideRoot_Rejected() {
        assertThatThrownBy(() -> storage.deleteByPrefix("/etc"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("STORAGE_INVALID_KEY");
    }
}
