package com.isim.dims.service;

import com.isim.dims.entity.DataSlice;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「重点事件统计」导出 Excel 单测（{@code exportKeyEventsXlsx}）。
 *
 * <p>锁死三件事：</p>
 * <ol>
 *   <li><b>列与页面一致</b>：事件 / VIN / 触发时刻 / 软件版本，顺序不能变（用户明确要求）；</li>
 *   <li><b>时间按北京时间格式化</b>：库里是 UTC 墙钟，直接写会与页面差 8 小时；</li>
 *   <li><b>筛选口径与页面同源</b>：窗口复用 key-events 的解析，事件下拉的值要下推成单事件。</li>
 * </ol>
 *
 * @feature: mxf
 */
class MxfKeyEventsExportTest extends MxfPanelTestSupport {

    @BeforeEach
    void useV6Events() {
        // 共享夹具默认给的是 v5 的 6 项（旧 /window 用例要用）；导出走的是 v6 的 8 项
        config.setEventOptions(new ArrayList<>(KEY_EVENTS_8));
        config.setDefaultEvents(new ArrayList<>(KEY_EVENTS_8));
    }

    @Test
    @DisplayName("表头 4 列与页面一致，且按北京时间写文本")
    void 导出_列与时间格式() throws Exception {
        LocalDateTime utc = LocalDateTime.of(2026, 9, 18, 2, 0); // 北京 10:00
        DataSlice withVersion = slice("sl-1", VIN_A, "unp_tkov_colli", utc, utc);
        withVersion.setSystemName("LUS-V7.0.6-6C8650A0C1");
        DataSlice noVersion = slice("sl-2", VIN_B, "system_crash",
                utc.plusMinutes(30), utc.plusMinutes(30));
        givenExportRows(withVersion, noVersion);

        byte[] bytes = service.exportKeyEventsXlsx("MMT", null, "today", null, null, null);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("重点事件统计");

            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("事件");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("VIN");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("触发时刻");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("软件版本");
            assertThat(header.getLastCellNum()).isEqualTo((short) 4);

            // 明细按触发时刻倒序：noVersion(02:30 UTC) 在前
            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("系统崩溃");
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo(VIN_B);
            assertThat(first.getCell(2).getStringCellValue()).isEqualTo("2026-09-18 10:30:00");
            assertThat(first.getCell(3).getStringCellValue()).isEqualTo("-");

            Row second = sheet.getRow(2);
            assertThat(second.getCell(0).getStringCellValue()).isEqualTo("UNP碰撞");
            assertThat(second.getCell(2).getStringCellValue()).isEqualTo("2026-09-18 10:00:00");
            assertThat(second.getCell(3).getStringCellValue()).isEqualTo("LUS-V7.0.6-6C8650A0C1");

            assertThat(sheet.getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("无数据：只有表头，不抛异常")
    void 导出_空数据只有表头() throws Exception {
        givenExportRows();

        byte[] bytes = service.exportKeyEventsXlsx("MMT", null, "today", null, null, null);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isZero();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("事件");
        }
    }

    @Test
    @DisplayName("事件下拉的选中值下推成单事件；未选则用配置的 8 项")
    void 导出_事件筛选下推() {
        givenExportRows();

        service.exportKeyEventsXlsx("MMT", null, "week", null, null, "unp_tkov_colli");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> events = ArgumentCaptor.forClass(List.class);
        verify(dataSliceMapper).selectKeyEventSlices(any(), events.capture(), any(), any(), anyInt());
        assertThat(events.getValue()).containsExactly("unp_tkov_colli");
    }

    @Test
    @DisplayName("非法事件值按全部导出（不报错）")
    void 导出_非法事件值按全部() {
        givenExportRows();

        service.exportKeyEventsXlsx("MMT", null, "week", null, null, "not_an_event");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> events = ArgumentCaptor.forClass(List.class);
        verify(dataSliceMapper).selectKeyEventSlices(any(), events.capture(), any(), any(), anyInt());
        assertThat(events.getValue()).hasSize(8);
    }

    @Test
    @DisplayName("窗口与页面同源：本周 = 北京时间本周一 00:00 起")
    void 导出_窗口与页面同源() {
        givenExportRows();

        service.exportKeyEventsXlsx("MMT", null, "week", null, null, null);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dataSliceMapper).selectKeyEventSlices(any(), any(), from.capture(), to.capture(), anyInt());

        LocalDate monday = LocalDate.now(DISPLAY_ZONE)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        assertThat(from.getValue()).isEqualTo(beijingDayAt(monday, 0));
        assertThat(to.getValue()).isEqualTo(beijingDayAt(monday.plusWeeks(1), 0));
    }

    private void givenExportRows(DataSlice... slices) {
        when(dataSliceMapper.selectKeyEventSlices(any(), any(), any(), any(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(slices)));
    }
}
