package com.smartkpi.io;

import com.smartkpi.model.BranchMetric;
import org.apache.poi.ss.usermodel.*;

import java.util.List;
import java.util.Map;

public class TemplateWriter {
    public void writeResourceMetrics(Sheet sheet, List<BranchMetric> metrics, Map<String, Integer> cols) {
        int dataStart = HeaderLocator.DATA_START;
        clearDataRegion(sheet, cols, dataStart, Math.max(sheet.getLastRowNum(), dataStart + metrics.size() + 50));

        for (int i = 0; i < metrics.size(); i++) {
            BranchMetric m = metrics.get(i);
            int rowIdx = dataStart + i;
            Row row = sheet.getRow(rowIdx);
            if (row == null) row = sheet.createRow(rowIdx);

            write(row, cols.get("branch"), m.branchName());
            write(row, cols.get("deviceTotal"), m.deviceTotal());
            write(row, cols.get("alarmDuration"), m.sumResourceDuration());
            write(row, cols.get("businessTime"), m.businessTime());
            write(row, cols.get("resourceRate"), m.computedRate());
        }
    }

    private void clearDataRegion(Sheet sheet, Map<String, Integer> cols, int start, int end) {
        for (int r = start; r <= end; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Integer c : cols.values()) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                cell.setBlank();
            }
        }
    }

    private void write(Row row, Integer col, Object value) {
        if (col == null) return;
        Cell cell = row.getCell(col, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else {
            cell.setCellValue(value.toString());
        }
    }
}