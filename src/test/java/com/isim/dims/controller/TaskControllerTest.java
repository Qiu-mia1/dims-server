package com.isim.dims.controller;

import com.isim.dims.dto.TaskResponse;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
@Import(SliceSecurityTestConfig.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IngestionService ingestionService;

    // ============ GET /v1/tasks/{taskId} ============

    @Test
    void getTask_Found_Returns200() throws Exception {
        TaskResponse task = new TaskResponse();
        task.setTaskId("task-1");
        task.setSliceId("slice-1");
        task.setStatus("READY");
        task.setProgress(100);
        task.setStatusHistory(List.of());

        when(ingestionService.getTask("task-1")).thenReturn(task);

        mockMvc.perform(get("/v1/tasks/{taskId}", "task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value("task-1"))
                .andExpect(jsonPath("$.slice_id").value("slice-1"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.status_history").isArray());
    }

    @Test
    void getTask_NotFound_Returns500() throws Exception {
        when(ingestionService.getTask("invalid"))
                .thenThrow(new RuntimeException("TASK_NOT_FOUND: invalid"));

        mockMvc.perform(get("/v1/tasks/{taskId}", "invalid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void getTask_ErrorStatus_Returns200() throws Exception {
        TaskResponse task = new TaskResponse();
        task.setTaskId("task-error");
        task.setSliceId("slice-1");
        task.setStatus("ERROR");
        task.setProgress(70);
        task.setErrorMsg("Parsing failed");
        task.setStatusHistory(List.of());

        when(ingestionService.getTask("task-error")).thenReturn(task);

        mockMvc.perform(get("/v1/tasks/{taskId}", "task-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.error_message").value("Parsing failed"))
                .andExpect(jsonPath("$.progress").value(70))
                .andExpect(jsonPath("$.status_history").isArray());
    }

    // ============ GET /v1/health ============

    @Test
    void health_ReturnsUp() throws Exception {
        mockMvc.perform(get("/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("dims"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void health_ReturnsValidTimestamp() throws Exception {
        mockMvc.perform(get("/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
