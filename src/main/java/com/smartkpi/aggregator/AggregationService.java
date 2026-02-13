package com.smartkpi.aggregator;

import com.smartkpi.aggregator.Domain.DepartConfig;
import com.smartkpi.aggregator.Domain.DeviceRate;
import com.smartkpi.aggregator.Domain.MetricType;
import com.smartkpi.aggregator.Domain.OutputRow;
import com.smartkpi.aggregator.Domain.ProcessingLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AggregationService {

    public List<OutputRow> buildRows(List<DeviceRate> deviceRates,
                                     Map<String, DepartConfig> departMap,
                                     Set<String> canceledBranches,
                                     MetricType metricType,
                                     ProcessingLog log) {
        Map<String, List<DeviceRate>> byBranch = new HashMap<>();
        Map<String, DeviceRate> byTerminal = new HashMap<>();
        Map<String, Boolean> branchHasDepart = new HashMap<>();

        for (DepartConfig cfg : departMap.values()) {
            if (!cfg.includeInBranchAverage()) {
                String branch = Domain.normalizeName(cfg.branchName());
                branchHasDepart.put(branch, true);
            }
        }

        for (DeviceRate deviceRate : deviceRates) {
            byBranch.computeIfAbsent(Domain.normalizeName(deviceRate.branchName()), k -> new ArrayList<>()).add(deviceRate);
            byTerminal.put(deviceRate.terminalId(), deviceRate);
            if (!departMap.containsKey(deviceRate.terminalId())) {
                log.addMissingInConfig(deviceRate.terminalId());
            }
        }

        List<OutputRow> merged = new ArrayList<>();
        for (Map.Entry<String, List<DeviceRate>> entry : byBranch.entrySet()) {
            String branch = entry.getKey();
            if (canceledBranches.contains(branch)) {
                continue;
            }
            int total = 0;
            int included = 0;
            int depart = 0;
            double sum = 0;
            for (DeviceRate dr : entry.getValue()) {
                total++;
                DepartConfig cfg = departMap.get(dr.terminalId());
                boolean include = cfg == null || cfg.includeInBranchAverage();
                if (include) {
                    if (dr.rate() != null) {
                        included++;
                        sum += dr.rate();
                    }
                } else {
                    depart++;
                }
            }
            Double branchRate = null;
            if (included > 0) {
                branchRate = RateParser.round2(sum / included);
            } else {
                log.addEmptyBranch(branch);
            }
            boolean hasDepart = branchHasDepart.getOrDefault(branch, false);
            log.addBranchSummary(branch + ": 总设备=" + total + ", 纳入均值=" + included + ", 离行=" + depart + ", 率=" + (branchRate == null ? "-" : branchRate)
                    + ", 中间列策略=" + (hasDepart ? "清空" : "保留模板原值"));
            merged.add(new OutputRow(0, branch, branchRate, true, !hasDepart, branch));
        }

        int departTotal = 0;
        int departMatched = 0;
        int departMissing = 0;

        for (DepartConfig cfg : departMap.values()) {
            if (cfg.includeInBranchAverage()) {
                continue;
            }
            String branch = Domain.normalizeName(cfg.branchName());
            if (canceledBranches.contains(branch)) {
                continue;
            }
            departTotal++;
            DeviceRate dr = byTerminal.get(cfg.terminalId());
            Double value;
            if (metricType == MetricType.BOOT && cfg.bootFixed() != null) {
                value = cfg.bootFixed();
            } else if (metricType == MetricType.RESOURCE && cfg.resourceFixed() != null) {
                value = cfg.resourceFixed();
            } else if (dr != null) {
                value = dr.rate();
            } else if (metricType == MetricType.RESOURCE) {
                value = 0d;
                log.addDefaultResourceZero("离行设备在资源预警设备表缺失，已按默认0处理：终端编号=" + cfg.terminalId() + "，名称=" + cfg.outputName());
            } else {
                value = null;
            }

            if (dr == null) {
                departMissing++;
                log.addMissingInDevice("终端编号=" + cfg.terminalId() + "，名称=" + cfg.outputName());
            } else {
                departMatched++;
            }
            if (metricType == MetricType.RESOURCE && !cfg.managedByBranch() && cfg.resourceFixed() == null && dr == null) {
                value = 0d;
            }
            String name = Domain.blank(cfg.outputName()) ? branch + "(离行)" : cfg.outputName();
            merged.add(new OutputRow(0, name, value == null ? null : RateParser.round2(value), false, false, null));
        }

        log.addTerminalMatchStats("离行目录终端总数=" + departTotal + "，设备表匹配=" + departMatched + "，缺失=" + departMissing);

        Comparator<OutputRow> comp = Comparator.comparing(OutputRow::rate, Comparator.nullsLast(Double::compareTo));
        if (metricType == MetricType.BOOT) {
            comp = comp.reversed();
        }
        merged.sort(comp);

        List<OutputRow> result = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            OutputRow r = merged.get(i);
            result.add(new OutputRow(i + 1, r.name(), r.rate(), r.branchRow(), r.preserveMiddleColumns(), r.sourceBranchName()));
        }
        return result;
    }

    public Set<String> normalizeSet(Set<String> raw) {
        Set<String> normalized = new HashSet<>();
        for (String s : raw) {
            if (!Domain.blank(s)) {
                normalized.add(Domain.normalizeName(s));
            }
        }
        return normalized;
    }
}