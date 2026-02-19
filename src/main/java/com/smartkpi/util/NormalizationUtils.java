package com.smartkpi.util;

public final class NormalizationUtils {
    private NormalizationUtils() {}

    public static String normalizeTerminalId(String raw) {
        if (raw == null) return "";
        String trimmed = toHalfWidth(raw).trim().toUpperCase();
        return trimmed.replaceAll("^[^A-Z0-9]+|[^A-Z0-9]+$", "");
    }

    public static String normalizeBranchName(String raw) {
        if (raw == null) return "";
        return toHalfWidth(raw).trim().replaceAll("\\s+", "");
    }

    private static String toHalfWidth(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            if (c == 12288) {
                sb.append(' ');
            } else if (c >= 65281 && c <= 65374) {
                sb.append((char) (c - 65248));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}