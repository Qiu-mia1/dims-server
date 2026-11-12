package com.isim.dims.controller;

import com.isim.dims.config.StorageConfig;
import com.isim.dims.exception.StorageNotFoundException;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.StorageStat;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.util.StorageKeys;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VideoStreamingController 目录穿越回归测试（S2）
 *
 * <p>该端点挂载在匿名白名单 /data/videos/**（HTML5 video 无法带 Authorization 头），
 * 穿越防御必须由控制器自身承担（CVE-2018-1271 同类风险）：
 * ① sliceId UUID + filename 白名单（排除 / 与 ..）；
 * ② getCanonicalPath 前缀包含校验（symlink 逃逸在 canonical 层被拦截）；
 * ③ 任何拒绝统一 404，不泄露文件存在性。</p>
 */
@WebMvcTest(VideoStreamingController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({SliceSecurityTestConfig.class, VideoStreamingControllerTest.SyncAsyncConfig.class})
class VideoStreamingControllerTest {

    /**
     * 异步任务同步化：StreamingResponseBody 由 SimpleAsyncTaskExecutor 线程写体，
     * 与主线程 security HeaderWriterFilter 的响应头写入（doHeadersAfter）并发
     * 修改 MockHttpServletResponse 头映射 → ConcurrentModificationException。
     * 改为同步执行后写体发生在 perform() 内部主线程上，竞态按构造消除。
     */
    @TestConfiguration
    static class SyncAsyncConfig implements WebMvcConfigurer {
        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.setTaskExecutor(Runnable::run);
        }
    }

    /** 有效 UUID（8-4-4-4-12） */
    private static final String SLICE_ID = "95c67917-8344-497f-ac86-e6ed2749d6d7";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StorageConfig storageConfig;

    /** 存储门面 mock：控制器唯一存储入口（stat 默认不存在 → 404 路径） */
    @MockBean
    private StorageAccess storageAccess;

    @TempDir
    private Path tempDir;

    private static final byte[] FAKE_VIDEO_BYTES = "fake-mp4-bytes".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        when(storageConfig.getVideoDir()).thenReturn(tempDir.toAbsolutePath().toString());
        // resolveReadKey 透传 key；stat 默认抛不存在（nonexistent/symlink 用例 404）；
        // open 返回假视频字节（validFile 用例 StreamingResponseBody 消费）
        when(storageAccess.resolveReadKey(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(storageAccess.stat(anyString())).thenThrow(new StorageNotFoundException("mock: not found"));
        when(storageAccess.open(anyString())).thenReturn(new ByteArrayInputStream(FAKE_VIDEO_BYTES));
    }

    @Test
    void validFile_Returns200() throws Exception {
        Path dir = tempDir.resolve(SLICE_ID);
        Files.createDirectories(dir);
        Files.write(dir.resolve("cam_front.mp4"), FAKE_VIDEO_BYTES);

        // 有效文件：stat 返回元信息（contentType null → 控制器按扩展名兜底 video/mp4）。
        // doReturn 形式：when() 内嵌调用会命中 setUp 的 thenThrow stub 先抛异常
        String key = StorageKeys.video(SLICE_ID, "cam_front.mp4");
        doReturn(new StorageStat(FAKE_VIDEO_BYTES.length, null)).when(storageAccess).stat(key);

        mockMvc.perform(get("/data/videos/" + SLICE_ID + "/cam_front.mp4"))
                .andExpect(status().isOk());
    }

    @Test
    void nonUuidSliceId_Returns404() throws Exception {
        // 防线 ①：sliceId 非 UUID → 404
        mockMvc.perform(get("/data/videos/not-a-uuid/cam_front.mp4"))
                .andExpect(status().isNotFound());
    }

    @Test
    void dotDotFilename_IsRejected() throws Exception {
        // 防线 ① + 路由层：filename 为 ".."（父目录引用）→ 拒绝
        // （PathPattern 对 ".." 段的严格校验在路由层即返回 400；若绕过路由层
        // 命中控制器，白名单层同样拒绝。回归断言：请求绝不成功。）
        mockMvc.perform(get("/data/videos/" + SLICE_ID + "/.."))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void encodedTraversal_IsRejected() throws Exception {
        // 防线 ①：编码穿越（CVE-2018-1271 同款 ..%2f 绕过）→ 拒绝
        // （%2f/%2e 解码后形成 ".." 段，路由层 400；即便不触发路由层校验，
        // 命中控制器时 UUID/文件名白名单同样拒绝。回归断言：请求绝不成功。）
        mockMvc.perform(get("/data/videos/..%2f..%2fetc/passwd"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/data/videos/" + SLICE_ID + "/..%2f..%2fsecret"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/data/videos/" + SLICE_ID + "/a%2fb.mp4"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void symlinkEscapingVideoDir_Returns404() throws Exception {
        // 防线 ②：合法 UUID + 合法 filename，但文件为指向视频根目录外的符号链接
        // → canonical 路径不在根目录内 → 404（symlink 逃逸回归测试）
        Path dir = tempDir.resolve(SLICE_ID);
        Files.createDirectories(dir);
        Path link = dir.resolve("evil.mp4");
        try {
            Files.createSymbolicLink(link, Path.of("/etc/hostname"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "symlink not supported on this platform: " + e.getMessage());
        }

        mockMvc.perform(get("/data/videos/" + SLICE_ID + "/evil.mp4"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonexistentFile_Returns404() throws Exception {
        // 正常路径：文件不存在 → 404（与拒绝穿越请求行为一致，不泄露信息）
        mockMvc.perform(get("/data/videos/" + SLICE_ID + "/missing.mp4"))
                .andExpect(status().isNotFound());
    }
}
