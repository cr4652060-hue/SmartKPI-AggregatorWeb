package com.smartkpi.io;

import com.smartkpi.core.ProcessingLogger;
import com.smartkpi.model.DeviceRecord;
import com.smartkpi.util.NormalizationUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class DeviceLoader {
    public Map<String, DeviceRecord> load(Path deviceFile, ProcessingLogger logger) throws IOException {
        return load(deviceFile, logger, "设备表");
    }

    public Map<String, DeviceRecord> load(Path deviceFile, ProcessingLogger logger, String label) throws IOException {
        Map<String, DeviceRecord> map = new LinkedHashMap<>();
        int total = 0;
        int valid = 0;
        int duplicate = 0;
        int conflict = 0;

        try (Workbook workbook = WorkbookFactory.create(deviceFile.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            int headerRowIndex = findHeaderRow(sheet);
            Map<String, Integer> cols = locateColumns(sheet.getRow(headerRowIndex));

            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                total++;
                String terminalId = NormalizationUtils.normalizeTerminalId(readString(row, cols.get("terminal")));
                if (terminalId.isBlank()) continue;
                valid++;
                DeviceRecord record = new DeviceRecord(
                        NormalizationUtils.normalizeBranchName(readString(row, cols.get("branch"))),
                        terminalId,
                        readDouble(row, cols.get("bootRate")),
                        readDouble(row, cols.get("resourceRate")),
                        readDouble(row, cols.get("duration")),
                        readDouble(row, cols.get("businessTime"))
                );

                DeviceRecord existing = map.get(terminalId);
                if (existing != null) {
                    duplicate++;
                    boolean replace = existing.resourceRate() == null && record.resourceRate() != null;
                    if (!replace && !Objects.equals(existing.resourceRate(), record.resourceRate())) {
                        conflict++;
                    }
                    if (replace) map.put(terminalId, record);
                } else {
                    map.put(terminalId, record);
                }
            }
        }

        logger.section(label + "加载");
        logger.log("总行数=" + total);
        logger.log("有效终端数=" + valid);
        logger.log("去重后终端数=" + map.size());
        logger.log("重复终端数=" + duplicate);
        logger.log("冲突终端数=" + conflict);
        return map;
    }

    private int findHeaderRow(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String all = readWholeRow(row);
            if (all.contains("终端") && (all.contains("所属") || all.contains("网点"))) {
                return r;
            }
        }
        return 0;
    }

    private String readWholeRow(Row row) {
        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            sb.append(formatter.formatCellValue(row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim());
        }
        return sb.toString();
    }

    private Map<String, Integer> locateColumns(Row header) {
        Map<String, Integer> cols = new HashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String v = formatter.formatCellValue(header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
            if (v.contains("终端")) cols.put("terminal", c);
            if (v.contains("所属机构") || v.contains("所属网点") || v.equals("网点")) cols.put("branch", c);
            if (v.contains("开机率")) cols.put("bootRate", c);
            if (v.contains("资源预警率")) cols.put("resourceRate", c);
            if (v.contains("停机") && v.contains("时长")) cols.put("duration", c);
            if (v.contains("合计") && v.contains("时长")) cols.put("duration", c);
            if (v.contains("营业时长")) cols.put("businessTime", c);
        }
        cols.putIfAbsent("terminal", 1);
        cols.putIfAbsent("branch", 0);
        return cols;
    }

    private String readString(Row row, Integer idx) {
        if (idx == null) return "";
        Cell c = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> new DataFormatter().formatCellValue(c);
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }

    private Double readDouble(Row row, Integer idx) {
        String v = readString(row, idx).replace("%", "").trim();
        if (v.isEmpty()) return null;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}