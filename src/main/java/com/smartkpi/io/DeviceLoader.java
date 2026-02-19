package com.smartkpi.io;

import com.smartkpi.core.ProcessingLogger;
import com.smartkpi.model.DeviceRecord;
import com.smartkpi.util.NormalizationUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class DeviceLoader {
    public Map<String, DeviceRecord> load(Path deviceFile, ProcessingLogger logger) throws IOException {
        Map<String, DeviceRecord> map = new LinkedHashMap<>();
        int total = 0;
        int valid = 0;
        int duplicate = 0;

        try (Workbook workbook = WorkbookFactory.create(deviceFile.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                total++;
                String terminalId = NormalizationUtils.normalizeTerminalId(readString(row, 1));
                if (terminalId.isBlank()) continue;
                valid++;
                DeviceRecord record = new DeviceRecord(
                        NormalizationUtils.normalizeBranchName(readString(row, 0)),
                        terminalId,
                        readDouble(row, 2),
                        readDouble(row, 3),
                        readDouble(row, 4),
                        readDouble(row, 5)
                );
                if (map.put(terminalId, record) != null) duplicate++;
            }
        }

        logger.section("设备表加载");
        logger.log("设备表总行数=" + total);
        logger.log("有效终端数=" + valid);
        logger.log("重复终端数=" + duplicate);
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