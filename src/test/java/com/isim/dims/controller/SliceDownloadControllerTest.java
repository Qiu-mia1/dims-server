package com.isim.dims.controller;

import com.isim.dims.security.DownloadTicketService;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.DataSliceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SliceDownloadController.class)
@Import(SliceSecurityTestConfig.class)
class SliceDownloadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSliceService dataSliceService;

    @MockBean
    private DownloadTicketService downloadTicketService;

    @Test
    void downloadCarla_ReturnsZipAttachment() throws Exception {
        DataSliceService.CarlaDownload download = new DataSliceService.CarlaDownload(
                "route_carla.zip", List.of("scene_data/s1/carla/s1.xosc"));
        when(dataSliceService.prepareCarlaDownload("s1")).thenReturn(download);
        doAnswer(invocation -> null).when(dataSliceService)
                .writeCarlaZip(eq(download), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(get("/v1/data-slices/s1/downloads/carla"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("route_carla.zip")));
    }

    /**
     * 回归：取票只发票据，**不得**打开 bag 数据流。
     *
     * <p>曾经的实现调 {@code downloadRosbag()} 再 {@code try (InputStream ignored = ...)}，
     * 而 HttpClient 的实体流在 close() 时会把未读内容读干 → 356MB 的 bag 让取票阻塞 89s，
     * 前端 60s 超时 → 用户看到「点击下载没反应」。这里用 {@code never()} 把这个坑钉死。
     */
    @Test
    void createRosbagTicket_IssuesTicketWithoutTouchingDataStream() throws Exception {
        when(downloadTicketService.issue("s1")).thenReturn("signed-ticket");

        mockMvc.perform(get("/v1/data-slices/s1/downloads/rosbag/ticket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").value("signed-ticket"));

        verify(dataSliceService).assertRosbagDownloadable("s1");
        verify(dataSliceService, never()).downloadRosbag(anyString());
    }

    /** 浏览器导航不带 Authorization，缺票/坏票必须 403（不能是 500 DOWNLOAD_FAILED）。 */
    @Test
    void downloadRosbagNative_RejectsMissingOrBadTicketWith403() throws Exception {
        mockMvc.perform(get("/v1/data-slices/s1/downloads/rosbag/native"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/v1/data-slices/s1/downloads/rosbag/native").param("ticket", "bad-ticket"))
                .andExpect(status().isForbidden());

        verify(dataSliceService, never()).downloadRosbag(anyString());
    }

    // ============ 日志下载 ============

    private static DataSliceService.LogDownload singleFileLog() {
        return new DataSliceService.LogDownload("only.log", false,
                List.of(new DataSliceService.LogDownload.LogFile("/data/x/only.log", "only.log", 9L)), 9L,
                new DataSliceService.LogSource("local", "local-filesystem", "/data/x"));
    }

    private static DataSliceService.LogDownload multiFileLog() {
        return new DataSliceService.LogDownload("case_logs.zip", true,
                List.of(new DataSliceService.LogDownload.LogFile("/data/x/a.log", "a.log", 5L),
                        new DataSliceService.LogDownload.LogFile("/data/x/log/b.log", "log/b.log", 7L)),
                12L, new DataSliceService.LogSource("local", "local-filesystem", "/data/x"));
    }

    /**
     * 取票必须完成全部可达性校验（路径/是否有日志/体积），并回传文件名。
     *
     * <p>「路径不对」「该目录没有日志」「日志过大」这些原因只能在这里抛给用户 ——
     * 原生下载是 {@code <a>} 导航，拿不到错误响应体。
     *
     * <p>响应还要带上本次<b>实际下载范围</b>（命中目录 / 文件数 / 文件清单），
     * 让调用方在下载前就能确认「下的到底是不是这个目录」。
     */
    @Test
    void createLogDownloadTicket_ReturnsTicketAndScope() throws Exception {
        when(dataSliceService.prepareLogDownload("s1")).thenReturn(singleFileLog());
        when(downloadTicketService.issue("s1")).thenReturn("signed-ticket");

        mockMvc.perform(get("/v1/data-slices/s1/downloads/log/ticket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").value("signed-ticket"))
                .andExpect(jsonPath("$.file_name").value("only.log"))
                .andExpect(jsonPath("$.file_count").value(1))
                .andExpect(jsonPath("$.total_size").value(9))
                .andExpect(jsonPath("$.source_dir").value("/data/x"))
                .andExpect(jsonPath("$.files[0]").value("only.log"));
    }

    @Test
    void downloadLogNative_RejectsMissingOrBadTicketWith403() throws Exception {
        mockMvc.perform(get("/v1/data-slices/s1/downloads/log/native"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/v1/data-slices/s1/downloads/log/native").param("ticket", "bad-ticket"))
                .andExpect(status().isForbidden());

        // 票据没过就不该去碰文件系统
        verify(dataSliceService, never()).prepareLogDownload(anyString());
    }

    /**
     * 单文件直出：必须声明 Content-Length，否则浏览器不知道要下多少。
     *
     * <p>走 {@code asyncStarted → asyncDispatch} 两步：{@code StreamingResponseBody} 是异步响应，
     * 直接在一次 {@code perform} 里断言 header 会在「异步线程写 header」与「测试线程读 header」
     * 之间产生竞态，偶发 {@code ConcurrentModificationException}
     * （{@code MockHttpServletResponse} 的 header map 不是线程安全的）。
     */
    @Test
    void downloadLog_SingleFile_StreamsRawWithExactContentLength() throws Exception {
        when(dataSliceService.prepareLogDownload("s1")).thenReturn(singleFileLog());

        MvcResult pending = mockMvc.perform(get("/v1/data-slices/s1/downloads/log"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Length", "9"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("only.log")));
    }

    /**
     * 多文件打包：**绝不能**声明 Content-Length —— totalSize 是日志原始字节数，
     * 而 ZIP 压缩后长度无法预知，声明了会让响应被按原始长度截断/挂住。
     */
    @Test
    void downloadLog_MultipleFiles_ReturnsZipWithoutContentLength() throws Exception {
        when(dataSliceService.prepareLogDownload("s1")).thenReturn(multiFileLog());

        MvcResult pending = mockMvc.perform(get("/v1/data-slices/s1/downloads/log"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().doesNotExist("Content-Length"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("case_logs.zip")));
    }

    /** 目录下没有日志 → 404 LOG_NOT_FOUND，把目录实际内容带回给用户判断。 */
    @Test
    void downloadLog_NoLogFile_Returns404LogNotFound() throws Exception {
        when(dataSliceService.prepareLogDownload("s1"))
                .thenThrow(new RuntimeException("LOG_NOT_FOUND: no log file under /data/x (entries: meta, a.mfbag)"));

        mockMvc.perform(get("/v1/data-slices/s1/downloads/log"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LOG_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("a.mfbag")));
    }

    /** 日志体积超上限 → 413，而不是硬拉几百 MB。 */
    @Test
    void downloadLog_OversizedLogs_Returns413LogTooLarge() throws Exception {
        when(dataSliceService.prepareLogDownload("s1"))
                .thenThrow(new RuntimeException("LOG_TOO_LARGE: 734003200 bytes exceeds limit 52428800 bytes"));

        mockMvc.perform(get("/v1/data-slices/s1/downloads/log"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("LOG_TOO_LARGE"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("734003200")));
    }

    /** 路径不在允许的根目录内 → 403，且不能退化成 500 DOWNLOAD_FAILED。 */
    @Test
    void downloadLog_PathOutsideAllowedRoots_Returns403LogPathNotAllowed() throws Exception {
        when(dataSliceService.prepareLogDownload("s1"))
                .thenThrow(new RuntimeException("LOG_PATH_NOT_ALLOWED: /etc is outside allowed roots [/data]"));

        mockMvc.perform(get("/v1/data-slices/s1/downloads/log"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("LOG_PATH_NOT_ALLOWED"));
    }

    /** import_source_dir 为空（直接上传的切片）→ 404 LOG_PATH_MISSING，与「目录不存在」区分开。 */
    @Test
    void downloadLog_BlankImportSourceDir_Returns404LogPathMissing() throws Exception {
        when(dataSliceService.prepareLogDownload("s1"))
                .thenThrow(new RuntimeException("LOG_PATH_MISSING: data_slice.import_source_dir is empty, slice_id=s1"));

        mockMvc.perform(get("/v1/data-slices/s1/downloads/log"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LOG_PATH_MISSING"));
    }

    /** 目录不存在 → 404 LOG_PATH_NOT_FOUND。 */
    @Test
    void downloadLog_DirectoryMissing_Returns404LogPathNotFound() throws Exception {
        when(dataSliceService.prepareLogDownload("s1"))
                .thenThrow(new RuntimeException("LOG_PATH_NOT_FOUND: /data/x (missing, not a directory, or not readable)"));

        mockMvc.perform(get("/v1/data-slices/s1/downloads/log"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LOG_PATH_NOT_FOUND"));
    }

    /** 未知异常仍回落 500 DOWNLOAD_FAILED（既有行为不能被新码破坏）。 */
    @Test
    void downloadLog_UnexpectedFailure_FallsBackTo500DownloadFailed() throws Exception {
        when(dataSliceService.prepareLogDownload("s1"))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/v1/data-slices/s1/downloads/log"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DOWNLOAD_FAILED"));
    }
}
