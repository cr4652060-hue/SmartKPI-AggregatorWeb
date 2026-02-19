package com.smartkpi.io;

import org.apache.poi.ss.usermodel.*;

import java.util.HashMap;
import java.util.Map;

public class HeaderLocator {
    public static final int HEADER_ROW = 2;
    public static final int FILTER_ROW = 3;
    public static final int DATA_START = 4;

    public Map<String, Integer> locateResourceColumns(Sheet sheet) {
        Map<String, Integer> idx = new HashMap<>();
        Row header = sheet.getRow(HEADER_ROW);
        Row filter = sheet.getRow(FILTER_ROW);
        DataFormatter formatter = new DataFormatter();

        for (int c = 0; c <= Math.max(header.getLastCellNum(), filter.getLastCellNum()); c++) {
            String h = formatter.formatCellValue(header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
            String f = formatter.formatCellValue(filter.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
            if ("所属网点".equals(h)) idx.put("branch", c);
            if ("设备总台数".equals(h)) idx.put("deviceTotal", c);
            if ("营业时长".equals(h)) idx.put("businessTime", c);
            if ("资源预警率".equals(h)) idx.put("resourceRate", c);
            if ("合计".equals(h) && "次数".equals(f)) idx.put("alarmCount", c);
            if ("合计".equals(h) && "时长".equals(f)) idx.put("alarmDuration", c);
        }
        return idx;
    }
}