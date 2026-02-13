package com.smartkpi.aggregator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Domain {
    public enum MetricType {
        BOOT, RESOURCE
    }

    public record DeviceRate(String terminalId, String branchName, Double rate) {
    }

    public record DepartConfig(String terminalId,
                               String branchName,
                               boolean managedByBranch,
                               boolean includeInBranchAverage,
                               String outputName,
                               Double bootFixed,
                               Double resourceFixed) {
    }

    public record OutputRow(int seq,
                            String name,
                            Double rate,
                            boolean branchRow,
                            boolean preserveMiddleColumns,
                            String sourceBranchName) {
    }

    public static final class ProcessingLog {
        public final List<String> missingInDevice = new ArrayList<>();
        public final List<String> missingInConfig = new ArrayList<>();
        public final List<String> emptyBranchScope = new ArrayList<>();
        public final List<String> branchSummaries = new ArrayList<>();
        public final List<String> skippedFiles = new ArrayList<>();
        public final List<String> defaultResourceZero = new ArrayList<>();
        public final List<String> terminalMatchStats = new ArrayList<>();

        public void addBranchSummary(String s) {
            branchSummaries.add(s);
        }

        public void addMissingInDevice(String message) {
            if (!missingInDevice.contains(message)) {
                missingInDevice.add(message);
            }
        }

        public void addMissingInConfig(String terminalId) {
            if (!missingInConfig.contains(terminalId)) {
                missingInConfig.add(terminalId);
            }
        }

        public void addEmptyBranch(String branchName) {
            if (!emptyBranchScope.contains(branchName)) {
                emptyBranchScope.add(branchName);
            }
        }

        public void addSkippedFile(String msg) {
            skippedFiles.add(msg);
        }

        public void addDefaultResourceZero(String msg) {
            defaultResourceZero.add(msg);
        }

        public void addTerminalMatchStats(String msg) {
            terminalMatchStats.add(msg);
        }
    }

    public static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replace('（', '(').replace('）', ')').replaceAll("\\s+", " ");
    }

    public static String normalizeTerminalId(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return "";
        }
        if (v.endsWith(".0") && v.matches("\\d+\\.0")) {
            v = v.substring(0, v.length() - 2);
        }
        return v;
    }

    public static String requireNonBlank(String s, String field) {
        String v = normalizeName(s);
        if (v.isEmpty()) {
            throw new IllegalArgumentException(field + " 为空");
        }
        return v;
    }

    public static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static <T> T nvl(T v, T fallback) {
        return Objects.requireNonNullElse(v, fallback);
    }
}