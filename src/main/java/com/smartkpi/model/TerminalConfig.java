package com.smartkpi.model;

public record TerminalConfig(
        String branchName,
        String terminalId,
        boolean inScope,
        boolean inAverage,
        String installLocation,
        String outputBranchName,
        Double fixedBootRate,
        Double fixedResourceRate
) {
}