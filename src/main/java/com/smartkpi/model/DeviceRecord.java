package com.smartkpi.model;

public record DeviceRecord(
        String branchName,
        String terminalId,
        Double bootRate,
        Double resourceRate,
        Double resourceDuration,
        Double businessTime
) {
}