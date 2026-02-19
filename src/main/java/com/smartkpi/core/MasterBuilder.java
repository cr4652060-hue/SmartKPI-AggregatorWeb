package com.smartkpi.core;

import com.smartkpi.model.*;

import java.util.*;

public class MasterBuilder {
    public List<TerminalUnified> build(Map<String, TerminalConfig> configs,
                                       Map<String, DeviceRecord> devices,
                                       ProcessingLogger logger) {
        List<TerminalUnified> result = new ArrayList<>();

        int onlyDevice = 0;
        int onlyConfig = 0;

        Set<String> allTerminals = new LinkedHashSet<>();
        allTerminals.addAll(configs.keySet());
        allTerminals.addAll(devices.keySet());

        for (String terminal : allTerminals) {
            TerminalConfig cfg = configs.get(terminal);
            DeviceRecord rec = devices.get(terminal);
            if (cfg == null) onlyDevice++;
            if (rec == null) onlyConfig++;

            Double bootRate = cfg != null && cfg.fixedBootRate() != null ? cfg.fixedBootRate() : (rec == null ? null : rec.bootRate());
            Double resourceRate = cfg != null && cfg.fixedResourceRate() != null ? cfg.fixedResourceRate() : (rec == null ? null : rec.resourceRate());
            result.add(new TerminalUnified(cfg, rec, bootRate, resourceRate));
        }

        logger.section("终端匹配统计");
        logger.log("离行目录终端总数=" + configs.size());
        logger.log("设备表终端总数=" + devices.size());
        logger.log("设备表存在但目录缺失=" + onlyDevice);
        logger.log("目录存在但设备表无=" + onlyConfig);
        return result;
    }
}