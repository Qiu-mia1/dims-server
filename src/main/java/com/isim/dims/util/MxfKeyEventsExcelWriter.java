package com.isim.dims.util;

import com.isim.dims.dto.MxfEventRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 「重点事件统计」导出 Excel（.xlsx）的写入器。
 *
 * <h3>列必须与页面一致</h3>
 * <p>页面表格是「事件 / VIN / 触发时刻 / 软件版本」，导出就这 4 列，顺序不变。
 * 「下载」是操作列，不入表。</p>
 *
 * <h3>时间口径</h3>
 * <p>{@code data_slice} 的时间列是 UTC 的 {@code timestamp without time zone}，
 * 而页面按浏览器本地时区（业务上是东八区）展示 —— 导出统一按
 * {@code Asia/Shanghai} 格式化，避免「页面 10:18、Excel 02:18」这种对不上的情况。
 * 单元格写成<b>文本</b>而不是时间格式：时间格式会带 Excel 的显示差异，文本才是所见即所得。</p>
 *
 * <h3>空值</h3>
 * <p>VIN / 软件版本为空时写 {@code -}，与页面表格的兜底一致。</p>
 *
 * @feature: mxf
 */
public final class MxfKeyEventsExcelWriter {

    private static final String SHEET_NAME = "重点事件统计";

    /** 列顺序 = 页面表格顺序（事件 / VIN / 触发时刻 / 软件版本） */
    private static final String[] HEADERS = {"事件", "VIN", "触发时刻", "软件版本"};

    /** 列宽（字符数），按真实内容长度给：VIN 17 位、版本号常见 21 字符 */
    private static final int[] COLUMN_WIDTHS = {24, 24, 21, 30};

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    private MxfKeyEventsExcelWriter() {
    }

    /**
     * 把明细行写成 xlsx 字节流。
     *
     * @param records 明细（可为 null / 空，此时只输出表头）
     * @return xlsx 二进制内容
     */
    public static byte[] write(List<MxfEventRecord> records) {
        List<MxfEventRecord> rows = records == null ? List.of() : records;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle headerStyle = headerStyle(workbook);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (MxfEventRecord record : rows) {
                Row row = sheet.createRow(rowIndex++);
                setText(row, 0, titleOrName(record));
                setText(row, 1, firstNonBlank(record.getVinDisplay(), record.getVin()));
                setText(row, 2, formatTime(record.getTriggerTime()));
                setText(row, 3, firstNonBlank(record.getSystemName()));
            }

            // 表头冻结 + 筛选按钮：数据一多，用户第一件事就是自己再筛一遍
            sheet.createFreezePane(0, 1);
            if (!rows.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, rows.size(), 0, HEADERS.length - 1));
            }
            for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成重点事件统计 Excel 失败", e);
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static void setText(Row row, int column, String value) {
        row.createCell(column).setCellValue(value == null || value.isBlank() ? "-" : value);
    }

    /** 事件展示名：优先中文标题，缺失回落事件名，再兜底 '-' */
    private static String titleOrName(MxfEventRecord record) {
        String title = record.getEventTitle();
        if (title != null && !title.isBlank()) {
            return title;
        }
        return record.getEventName();
    }

    private static String firstNonBlank(String... candidates) {
        for (String value : candidates) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** UTC 墙钟 → 北京时间文本（与页面展示一致） */
    private static String formatTime(LocalDateTime utcTime) {
        if (utcTime == null) {
            return null;
        }
        return utcTime.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(DISPLAY_ZONE)
                .format(TIME_FORMAT);
    }
}
