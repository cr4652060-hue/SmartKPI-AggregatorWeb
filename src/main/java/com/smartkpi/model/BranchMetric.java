package com.smartkpi.model;

public record BranchMetric(
        String branchName,
        int inScopeCount,
        int inAvgSetSize,
        int offsiteOnly,
        int deviceTotal,
        double sumResourceDuration,
        double businessTime,
        String computedRate,
        int fixedOverrideCount,
        int missingRateCount
) {
}