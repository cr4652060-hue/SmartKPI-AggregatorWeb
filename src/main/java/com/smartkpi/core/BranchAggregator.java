package com.smartkpi.core;

import com.smartkpi.model.*;

import java.text.DecimalFormat;
import java.util.*;

public class BranchAggregator {
    public List<BranchMetric> aggregateResource(List<TerminalUnified> unified, ProcessingLogger logger) {
        Map<String, List<TerminalUnified>> byBranch = new LinkedHashMap<>();
        for (TerminalUnified u : unified) {
            String branch = resolveBranch(u);
            if (branch.isBlank()) continue;
            byBranch.computeIfAbsent(branch, k -> new ArrayList<>()).add(u);
        }

        List<BranchMetric> metrics = new ArrayList<>();
        DecimalFormat pct = new DecimalFormat("0.00%");

        logger.section("网点聚合统计");
        for (var e : byBranch.entrySet()) {
            String branch = e.getKey();
            int inScopeCount = 0;
            int inAvgSetSize = 0;
            int offsiteOnly = 0;
            int fixedOverrideCount = 0;
            int missingRateCount = 0;
            double sumResourceDuration = 0;
            double businessTime = 0;

            for (TerminalUnified u : e.getValue()) {
                TerminalConfig cfg = u.config();
                DeviceRecord rec = u.record();
                boolean inScope = cfg != null && cfg.inScope();
                boolean inAvg = cfg != null && cfg.inAverage();
                if (inScope && rec != null) inScopeCount++;
                if (inScope && rec == null) offsiteOnly++;

                if (inScope && inAvg && rec != null) {
                    inAvgSetSize++;
                    if (cfg.fixedResourceRate() != null) fixedOverrideCount++;
                    if (rec.resourceDuration() != null) {
                        sumResourceDuration += rec.resourceDuration();
                    } else {
                        missingRateCount++;
                    }
                    if (rec.businessTime() != null && rec.businessTime() > businessTime) {
                        businessTime = rec.businessTime();
                    }
                }
            }

            int deviceTotal = inScopeCount + offsiteOnly;
            String rate;
            if (inAvgSetSize == 0 || businessTime == 0) {
                rate = "-";
            } else {
                double value = sumResourceDuration / (businessTime * inAvgSetSize);
                rate = pct.format(value);
            }

            BranchMetric metric = new BranchMetric(branch, inScopeCount, inAvgSetSize, offsiteOnly,
                    deviceTotal, sumResourceDuration, businessTime, rate, fixedOverrideCount, missingRateCount);
            metrics.add(metric);

            logger.log(String.format("网点:%s inScopeCount=%d inAvgSetSize=%d offsiteOnly=%d deviceTotal=%d sumResourceDuration=%.2f businessTime=%.2f computedRate=%s",
                    branch, inScopeCount, inAvgSetSize, offsiteOnly, deviceTotal, sumResourceDuration, businessTime, rate));
        }

        return metrics;
    }

    private String resolveBranch(TerminalUnified u) {
        if (u.config() != null && u.config().outputBranchName() != null && !u.config().outputBranchName().isBlank()) {
            return u.config().outputBranchName().trim();
        }
        if (u.config() != null && u.config().branchName() != null) return u.config().branchName();
        if (u.record() != null && u.record().branchName() != null) return u.record().branchName();
        return "";
    }
}