package com.smartkpi.model;

public record TerminalUnified(
        TerminalConfig config,
        DeviceRecord record,
        Double bootRate,
        Double resourceRate
) {
    public boolean hasConfig() {
        return config != null;
    }

    public boolean hasRecord() {
        return record != null;
    }
}