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
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                total++;
                String terminalId = NormalizationUtils.normalizeTerminalId(readString(row, 1));
                if (terminalId.isBlank()) {
                    invalid++;
                    continue;
                }
                TerminalConfig cfg = new TerminalConfig(
                        NormalizationUtils.normalizeBranchName(readString(row, 0)),
                        terminalId,
                        "Y".equalsIgnoreCase(readString(row, 2).trim()),
                        "Y".equalsIgnoreCase(readString(row, 3).trim()),
                        readString(row, 4),
                        readString(row, 5),
                        readDouble(row, 6),
                        readDouble(row, 7)
                );
                if (map.put(terminalId, cfg) != null) {
                    duplicate++;
                }
            }
        }
        logger.section("离行目录加载");
        logger.log("目录总行数=" + total);
        logger.log("解析失败行=" + invalid);
        logger.log("终端重复映射=" + duplicate);
        return map;
    }

    private String readString(Row row, int idx) {
        Cell c = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> new DataFormatter().formatCellValue(c);
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }

    private Double readDouble(Row row, int idx) {
        String v = readString(row, idx).replace("%", "").trim();
        if (v.isEmpty()) return null;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}