package com.isim.dims.service.impl;

import com.isim.dims.dto.VinMd5ImportResponse;
import com.isim.dims.entity.VinMd5Mapping;
import com.isim.dims.mapper.VinMd5MappingMapper;
import com.isim.dims.service.VinMd5BackfillService;
import com.isim.dims.service.VinMd5ImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 车辆信息（VIN-MD5 映射）Excel 导入实现
 *
 * <h3>解析规则</h3>
 * <ul>
 *   <li>工作表：取第一个同时含 vin、md5 表头的工作表（多 Sheet 只解析该表）；</li>
 *   <li>表头：在前 {@value #HEADER_SCAN_ROWS} 行内查找，列名 trim 后忽略大小写匹配
 *       {@code vin} / {@code md5}（如 VIN、Vin、MD5、Md5 均可）；</li>
 *   <li>数据：表头行之后逐行读取，全空行跳过；VIN 归一化为大写、MD5 归一化为小写；</li>
 *   <li>校验：MD5 须为 32 位十六进制；同一 Excel 内 VIN 重复时以最后一条为准。</li>
 * </ul>
 *
 * <h3>写入方式</h3>
 * <p>按 {@value #BATCH_SIZE} 行一批走 {@code INSERT ... VALUES ... ON CONFLICT (vin) DO UPDATE}
 * 批量 upsert；某批整体失败（典型：MD5 已被其它 VIN 占用，撞 uk_md5 唯一约束）
 * 时自动降级为逐行写入，把失败行挑出来返回，不影响其余行。</p>
 *
 * <h3>导入后回写</h3>
 * <p>映射入库后异步触发 {@link VinMd5BackfillService}：以本次导入成功的 MD5 为
 * 条件，一条 SQL 批量 {@code UPDATE data_slice SET vin = ?, vin_md5_id = ?
 * WHERE import_md5 IN (...)}，把本地目录导入时映射缺失而留下的历史切片
 * （vin 为空或兜底成 vin=md5）纠正过来。回写失败不影响本次导入结果。</p>
 *
 * @feature: ingestion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VinMd5ImportServiceImpl implements VinMd5ImportService {

    /**
     * 表头搜索范围：只在前 N 行里找 vin / md5 列名
     */
    private static final int HEADER_SCAN_ROWS = 20;

    /**
     * 单批写入行数（避免 SQL 过长）
     */
    private static final int BATCH_SIZE = 500;

    /**
     * MD5 格式：32 位十六进制
     */
    private static final Pattern MD5_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    /**
     * 空白字符：含普通空白、不间断空格 \u00A0（网页/系统粘贴到 Excel 常带）、
     * 全角空格 \u3000 —— 单元格文本读取时整体剔除
     */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[\\s\\u00A0\\u3000]+");

    private final VinMd5MappingMapper vinMd5MappingMapper;

    /**
     * 映射入库后按 import_md5 异步回写历史切片的 vin / vin_md5_id
     */
    private final VinMd5BackfillService vinMd5BackfillService;

    @Override
    public VinMd5ImportResponse importExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("INVALID_FILE: 上传文件为空，请选择包含 vin、md5 两列的 Excel");
        }

        List<VinMd5ImportResponse.FailedItem> failed = new ArrayList<>();
        // key = vin（已归一化），value = 该 VIN 的最后一行数据（重复时后者覆盖前者）
        Map<String, ParsedRow> byVin = new LinkedHashMap<>();
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            for (Sheet sheet : workbook) {
                Header header = findHeader(sheet);
                if (header == null) {
                    continue;
                }
                log.info("vin-md5 import: sheet={}, headerRow={}, vinCol={}, md5Col={}",
                        sheet.getSheetName(), header.rowIndex + 1, header.vinCol, header.md5Col);
                totalRows = readRows(sheet, header, byVin, failed);
                break;
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("vin-md5 import: parse excel failed: file={}", file.getOriginalFilename(), e);
            throw new IllegalStateException("EXCEL_PARSE_FAILED: " + e.getMessage(), e);
        }

        if (byVin.isEmpty() && failed.isEmpty()) {
            throw new IllegalArgumentException("COLUMN_MISSING: Excel 必须包含 vin、md5 两列（列名忽略大小写）");
        }

        List<ParsedRow> rows = new ArrayList<>(byVin.values());
        List<String> importedMd5s = upsertAll(rows, failed);

        // 映射补齐后，异步把「导入时映射缺失」的历史切片 VIN 回写过来：
        // UPDATE data_slice SET vin = ?, vin_md5_id = ? WHERE import_md5 IN (...)
        // 只带本次导入成功的 MD5，失败行（如 MD5 已被其它 VIN 占用）不参与回写。
        if (!importedMd5s.isEmpty()) {
            vinMd5BackfillService.backfillAsync(importedMd5s);
        }

        VinMd5ImportResponse response = new VinMd5ImportResponse();
        response.setTotalRows(totalRows);
        response.setImportedCount(importedMd5s.size());
        response.setFailedCount(failed.size());
        response.setFailed(failed);
        log.info("vin-md5 import done: file={}, total={}, imported={}, failed={}",
                file.getOriginalFilename(), totalRows, importedMd5s.size(), failed.size());
        return response;
    }

    /* ==================== Excel 解析 ==================== */

    /**
     * 在工作表前 {@link #HEADER_SCAN_ROWS} 行内定位 vin / md5 列，找不到返回 null
     */
    private Header findHeader(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        int lastRow = Math.min(sheet.getLastRowNum(), HEADER_SCAN_ROWS - 1);
        for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null || row.getLastCellNum() < 0) {
                continue;
            }
            Integer vinCol = null;
            Integer md5Col = null;
            for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                String name = text(row.getCell(c), formatter).toLowerCase(Locale.ROOT);
                if (vinCol == null && "vin".equals(name)) {
                    vinCol = c;
                } else if (md5Col == null && "md5".equals(name)) {
                    md5Col = c;
                }
            }
            if (vinCol != null && md5Col != null) {
                return new Header(r, vinCol, md5Col);
            }
        }
        return null;
    }

    /**
     * 从表头下一行开始逐行读取，返回解析到的数据行数
     */
    private int readRows(Sheet sheet,
                         Header header,
                         Map<String, ParsedRow> byVin,
                         List<VinMd5ImportResponse.FailedItem> failed) {
        DataFormatter formatter = new DataFormatter();
        int total = 0;
        for (int r = header.rowIndex + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            // text() 已剔除全部空白字符（含首尾/中间空格、不间断空格、全角空格），此处无需再 trim
            String vin = text(row.getCell(header.vinCol), formatter);
            String md5 = text(row.getCell(header.md5Col), formatter);
            if (vin.isEmpty() && md5.isEmpty()) {
                continue; // 空行
            }
            total++;
            int rowNo = r + 1; // 展示给用户用 1-based 行号
            if (vin.isEmpty() || md5.isEmpty()) {
                failed.add(failedItem(rowNo, vin, md5, "MISSING_VALUE: vin 与 md5 均不能为空"));
                continue;
            }
            vin = vin.toUpperCase(Locale.ROOT);
            md5 = md5.toLowerCase(Locale.ROOT);
            if (!MD5_PATTERN.matcher(md5).matches()) {
                failed.add(failedItem(rowNo, vin, md5, "INVALID_MD5: md5 必须为 32 位十六进制字符串"));
                continue;
            }
            ParsedRow prev = byVin.put(vin, new ParsedRow(rowNo, vin, md5));
            if (prev != null) {
                failed.add(failedItem(prev.rowNo, prev.vin, prev.md5,
                        "DUPLICATE_VIN: 同一 Excel 内该 VIN 重复，已按最后一条导入"));
            }
        }
        return total;
    }

    /**
     * 单元格文本：null / 空单元格 → ""，否则剔除全部空白字符
     *
     * <p>剔除范围：首尾与中间的所有空白 —— 空格、制表符、换行、不间断空格
     * （从网页/系统粘贴到 Excel 常带 {@code \u00A0}）、全角空格 {@code \u3000}。
     * 目的是让带空格的 VIN / MD5 也能正确入库。</p>
     */
    private String text(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        try {
            return WHITESPACE_PATTERN.matcher(formatter.formatCellValue(cell)).replaceAll("");
        } catch (Exception e) {
            return "";
        }
    }

    /* ==================== 落库 ==================== */

    /**
     * 分批批量 upsert；整批失败时降级为逐行写入，隔离出失败行
     *
     * @return 成功写入的 MD5 清单（供后续异步回写 data_slice 使用）
     */
    private List<String> upsertAll(List<ParsedRow> rows,
                                   List<VinMd5ImportResponse.FailedItem> failed) {
        List<String> importedMd5s = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<ParsedRow> chunk = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            try {
                vinMd5MappingMapper.upsertBatch(toEntities(chunk));
                addMd5s(importedMd5s, chunk);
            } catch (Exception e) {
                log.warn("vin-md5 import: batch upsert failed ({} rows), fallback to row-by-row: {}",
                        chunk.size(), e.getMessage());
                upsertOneByOne(chunk, failed, importedMd5s);
            }
        }
        return importedMd5s;
    }

    /**
     * 逐行写入，失败行单独记录，成功行的 MD5 追加到 {@code importedMd5s}
     */
    private void upsertOneByOne(List<ParsedRow> rows,
                                List<VinMd5ImportResponse.FailedItem> failed,
                                List<String> importedMd5s) {
        for (ParsedRow row : rows) {
            try {
                vinMd5MappingMapper.upsertBatch(toEntities(List.of(row)));
                importedMd5s.add(row.md5);
            } catch (Exception e) {
                failed.add(failedItem(row.rowNo, row.vin, row.md5, dbReason(e)));
            }
        }
    }

    private void addMd5s(List<String> target, List<ParsedRow> rows) {
        for (ParsedRow row : rows) {
            target.add(row.md5);
        }
    }

    private List<VinMd5Mapping> toEntities(List<ParsedRow> rows) {
        List<VinMd5Mapping> entities = new ArrayList<>(rows.size());
        for (ParsedRow row : rows) {
            VinMd5Mapping entity = new VinMd5Mapping();
            entity.setVin(row.vin);
            entity.setMd5(row.md5);
            entities.add(entity);
        }
        return entities;
    }

    /**
     * 数据库异常 → 可读失败原因（最常见：MD5 已被其它 VIN 占用）
     */
    private String dbReason(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.contains("uk_md5") || msg.contains("vin_md5_mapping_md5_key")) {
            return "MD5_ALREADY_MAPPED: 该 MD5 已绑定其它 VIN";
        }
        String trimmed = msg.length() > 120 ? msg.substring(0, 120) : msg;
        return "DB_ERROR: " + trimmed;
    }

    private VinMd5ImportResponse.FailedItem failedItem(int rowNo, String vin, String md5, String reason) {
        VinMd5ImportResponse.FailedItem item = new VinMd5ImportResponse.FailedItem();
        item.setRow(rowNo);
        item.setVin(vin);
        item.setMd5(md5);
        item.setReason(reason);
        return item;
    }

    /**
     * 表头定位结果：行下标 + 两列列下标（均 0-based）
     */
    private static class Header {

        private final int rowIndex;
        private final int vinCol;
        private final int md5Col;

        Header(int rowIndex, int vinCol, int md5Col) {
            this.rowIndex = rowIndex;
            this.vinCol = vinCol;
            this.md5Col = md5Col;
        }
    }

    /**
     * 解析出的一行数据（Excel 行号 + 归一化后的 vin / md5）
     */
    private static class ParsedRow {

        private final int rowNo;
        private final String vin;
        private final String md5;

        ParsedRow(int rowNo, String vin, String md5) {
            this.rowNo = rowNo;
            this.vin = vin;
            this.md5 = md5;
        }
    }
}
