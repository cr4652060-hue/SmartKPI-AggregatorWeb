package com.smartkpi.aggregator;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalDouble;

public final class RateParser {
    private static final DataFormatter FORMATTER = new DataFormatter();

    private RateParser() {
    }

    public static OptionalDouble parseCell(Cell cell) {
        if (cell == null) {
            return OptionalDouble.empty();
        }
        return parseString(FORMATTER.formatCellValue(cell));
    }

    public static OptionalDouble parseString(String raw) {
        if (raw == null) {
            return OptionalDouble.empty();
        }
        String value = raw.trim().replace("％", "%");
        if (value.isEmpty() || "-".equals(value)) {
            return OptionalDouble.empty();
        }
        try {
            if (value.endsWith("%")) {
                double v = Double.parseDouble(value.substring(0, value.length() - 1).trim());
                return OptionalDouble.of(v);
            }
            double v = Double.parseDouble(value);
            if (v >= 0 && v <= 1) {
                return OptionalDouble.of(v * 100);
            }
            return OptionalDouble.of(v);
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
    }

    public static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}