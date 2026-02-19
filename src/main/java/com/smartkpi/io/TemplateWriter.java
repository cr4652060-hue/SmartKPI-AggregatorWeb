package com.smartkpi.io;

import org.apache.poi.ss.usermodel.*;

import java.util.*;

public class TemplateWriter {

    public void writeMetrics(Sheet sheet,
                             HeaderLocator.TemplateLayout layout,
                             List<Map<String, Object>> rows,
                             Set<String> writableKeys) {
        Row sample = Optional.ofNullable(sheet.getRow(layout.dataStartRowIndex()))
                .orElseGet(() -> sheet.createRow(layout.dataStartRowIndex()));

        Map<String, Integer> existingRowByBranch = buildExistingRowIndex(sheet, layout);

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> data = rows.get(i);
            String branch = Objects.toString(data.getOrDefault("branch", ""), "").trim();
            int rowIndex = existingRowByBranch.getOrDefault(branch, layout.dataStartRowIndex() + i);

            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                row = sheet.createRow(rowIndex);
                cloneRowStyle(sample, row);
            }
            writeCell(row, layout.columns().get("branch"), branch);

            for (String key : writableKeys) {
                if (!data.containsKey(key)) continue;
                writeCell(row, layout.columns().get(key), data.get(key));
            }
        }
    }

    private Map<String, Integer> buildExistingRowIndex(Sheet sheet, HeaderLocator.TemplateLayout layout) {
        Map<String, Integer> map = new LinkedHashMap<>();
        Integer branchCol = layout.columns().get("branch");
        if (branchCol == null) return map;
        DataFormatter formatter = new DataFormatter();
        for (int r = layout.dataStartRowIndex(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String branch = formatter.formatCellValue(row.getCell(branchCol, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
            if (!branch.isBlank()) {
                map.putIfAbsent(branch, r);
            }
        }
        return map;
    }

    private void cloneRowStyle(Row source, Row target) {
        target.setHeight(source.getHeight());
        for (int c = source.getFirstCellNum(); c < source.getLastCellNum(); c++) {
            if (c < 0) continue;
            Cell srcCell = source.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (srcCell == null) continue;
            Cell targetCell = target.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            targetCell.setCellStyle(srcCell.getCellStyle());
            if (srcCell.getCellType() == CellType.FORMULA) {
                targetCell.setCellFormula(srcCell.getCellFormula());
            }
        }
    }

    private void writeCell(Row row, Integer col, Object value) {
        if (col == null) return;
        Cell cell = row.getCell(col, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        if (value == null) {
            return;
        }
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else {
            cell.setCellValue(value.toString());
        }
    }
}