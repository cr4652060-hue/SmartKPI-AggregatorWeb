package com.smartkpi.io;

import com.smartkpi.core.ProcessingLogger;
import com.smartkpi.model.TerminalConfig;
import com.smartkpi.util.NormalizationUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class ConfigLoader {

    public Map<String, TerminalConfig> load(Path offsiteCatalog, ProcessingLogger logger) throws IOException {
        Map<String, TerminalConfig> map = new LinkedHashMap<>();
        int duplicate = 0;
        int invalid = 0;
        int total = 0;

        try (Workbook workbook = WorkbookFactory.create(offsiteCatalog.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            int headerRowIdx = 0;
            Map<String, Integer> cols = locateColumns(sheet.getRow(headerRowIdx));
            for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                total++;
                String terminalId = NormalizationUtils.normalizeTerminalId(readString(row, cols.get("terminal")));
                if (terminalId.isBlank()) {
                    invalid++;
                    continue;
                }
                TerminalConfig cfg = new TerminalConfig(
                        NormalizationUtils.normalizeBranchName(readString(row, cols.get("branch"))),
                        terminalId,
                        "Y".equalsIgnoreCase(readString(row, cols.get("inScope")).trim()),
                        "Y".equalsIgnoreCase(readString(row, cols.get("inAvg")).trim()),
                        readString(row, cols.get("installLocation")),
                        readString(row, cols.get("outputBranch")),
                        readDouble(row, cols.get("fixedBoot")),
                        readDouble(row, cols.get("fixedResource"))
                );
                if (map.put(terminalId, cfg) != null) duplicate++;
            }
        }
        logger.section("离行目录加载统计");
        logger.log("总行数=" + total);
        logger.log("成功行数=" + map.size());
        logger.log("失败行数=" + invalid);
        logger.log("重复终端=" + duplicate);
        return map;
    }

    private Map<String, Integer> locateColumns(Row header) {
        DataFormatter formatter = new DataFormatter();
        Map<String, Integer> cols = new HashMap<>();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String v = formatter.formatCellValue(header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
            if (v.contains("终端")) cols.put("terminal", c);
            if (v.contains("所属")) cols.put("branch", c);
            if (v.contains("接管") || v.contains("纳入本网点")) cols.put("inScope", c);
            if (v.contains("均值")) cols.put("inAvg", c);
            if (v.contains("安装位置")) cols.put("installLocation", c);
            if (v.contains("直接复制") || v.contains("输出")) cols.put("outputBranch", c);
            if (v.contains("开机率固定")) cols.put("fixedBoot", c);
            if (v.contains("资源预警率固定")) cols.put("fixedResource", c);
        }
        cols.putIfAbsent("branch", 0);
        cols.putIfAbsent("terminal", 1);
        cols.putIfAbsent("inScope", 2);
        cols.putIfAbsent("inAvg", 3);
        cols.putIfAbsent("installLocation", 4);
        cols.putIfAbsent("outputBranch", 5);
        cols.putIfAbsent("fixedBoot", 6);
        cols.putIfAbsent("fixedResource", 7);
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