package com.smartkpi.io;

import org.apache.poi.ss.usermodel.*;

import java.util.*;

public class HeaderLocator {
    private static final List<String> BRANCH_HEADERS = List.of("所属网点", "所属机构", "网点");

    public TemplateLayout locate(Sheet sheet, int configuredDataStart) {
        DataFormatter formatter = new DataFormatter();
        int headerRowIndex = findHeaderRow(sheet, formatter);
        if (headerRowIndex < 0) {
            throw new IllegalStateException("未识别机构模板表头行");
        }
        Row header = sheet.getRow(headerRowIndex);
        Map<String, Integer> cols = new HashMap<>();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String value = formatter.formatCellValue(header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
            if (BRANCH_HEADERS.contains(value)) cols.put("branch", c);
            if ("设备总台数".equals(value)) cols.put("deviceTotal", c);
            if ("营业时长".equals(value)) cols.put("businessTime", c);
            if (value.contains("资源预警率")) cols.put("resourceRate", c);
            if (value.contains("开机率")) cols.put("bootRate", c);
            if (value.contains("停机") && value.contains("时长")) cols.put("bootDuration", c);
            if ("合计".equals(value)) cols.put("totalTitle", c);
        }

        Row subHeader = headerRowIndex + 1 <= sheet.getLastRowNum() ? sheet.getRow(headerRowIndex + 1) : null;
        if (subHeader != null && cols.containsKey("totalTitle")) {
            int c = cols.get("totalTitle");
            String sub = formatter.formatCellValue(subHeader.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
            if ("时长".equals(sub)) cols.put("alarmDuration", c);
        }

        if (!cols.containsKey("alarmDuration")) {
            for (int c = 0; c < header.getLastCellNum(); c++) {
                String v = formatter.formatCellValue(header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
                if (v.contains("合计") && v.contains("时长")) {
                    cols.put("alarmDuration", c);
                    break;
                }
            }
        }

        int dataStart = configuredDataStart > 0 ? configuredDataStart - 1 : headerRowIndex + 1;
        return new TemplateLayout(headerRowIndex, dataStart, cols);
    }

    private int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 15); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            boolean hasBranch = false;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String value = formatter.formatCellValue(row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
                if (BRANCH_HEADERS.contains(value)) {
                    hasBranch = true;
                    break;
                }
            }
            if (hasBranch) return r;
        }
        return -1;
    }

    public record TemplateLayout(int headerRowIndex, int dataStartRowIndex, Map<String, Integer> columns) {}
}