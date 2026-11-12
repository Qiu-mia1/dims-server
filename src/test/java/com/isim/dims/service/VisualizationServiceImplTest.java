package com.isim.dims.service;

import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.exception.StorageArchivedException;
import com.isim.dims.mapper.CameraCalibrationMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.service.impl.VisualizationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VisualizationServiceImpl 单元测试（Review W3 补测）
 *
 * <p>minio 模式归档切片（storage_type=LOCAL）发起提取 → 服务层入队前
 * 立即抛 StorageArchivedException（Controller 映射 410），不得落任务。</p>
 */
@ExtendWith(MockitoExtension.class)
class VisualizationServiceImplTest {

    @Mock
    private SensorStreamMapper sensorStreamMapper;

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private CameraCalibrationMapper cameraCalibrationMapper;

    @Mock
    private VisualizationPipelineService visualizationPipelineService;

    @Mock
    private StorageAccess storageAccess;

    @InjectMocks
    private VisualizationServiceImpl visualizationService;

    private DataSlice archivedSlice() {
        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStorageType("LOCAL");
        return slice;
    }

    @Test
    void extractVideoSegment_ArchivedSlice_ThrowsStorageArchived() {
        DataSlice slice = archivedSlice();
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
        doThrow(new StorageArchivedException("slice", "slice-1"))
                .when(storageAccess).assertNotArchived(slice);

        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/camera/left"));

        assertThrows(StorageArchivedException.class,
                () -> visualizationService.extractVideoSegment("slice-1", request));
        // 归档切片不得创建提取任务
        verify(visualizationPipelineService, never())
                .createTask(anyString(), anyString(), any());
        verify(visualizationPipelineService, never())
                .processExtractVideo(anyString(), anyString(), anyString(), any());
    }

    @Test
    void extractSignalData_ArchivedSlice_ThrowsStorageArchived() {
        DataSlice slice = archivedSlice();
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
        doThrow(new StorageArchivedException("slice", "slice-1"))
                .when(storageAccess).assertNotArchived(slice);

        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/gnss/bestpos"));

        assertThrows(StorageArchivedException.class,
                () -> visualizationService.extractSignalData("slice-1", request));
        // 归档切片不得创建提取任务
        verify(visualizationPipelineService, never())
                .createTask(anyString(), anyString(), any());
        verify(visualizationPipelineService, never())
                .processExtractSignals(anyString(), anyString(), anyString(), any());
    }

    @Test
    void extractVideoSegment_SliceNotFound_Throws() {
        when(dataSliceMapper.selectById("slice-1")).thenReturn(null);

        ExtractRequest request = new ExtractRequest();
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> visualizationService.extractVideoSegment("slice-1", request));
        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("SLICE_NOT_FOUND");
    }
}
