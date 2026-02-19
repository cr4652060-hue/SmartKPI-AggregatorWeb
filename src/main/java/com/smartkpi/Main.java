package com.smartkpi;

import com.smartkpi.core.ProcessingLogger;
import com.smartkpi.io.*;
import com.smartkpi.model.*;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: java -jar smartkpi.jar <inputDir> <outputDir>");
        }

        Path inputDir = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        ProcessingLogger logger = new ProcessingLogger();

        InputFiles files = new InputResolver().resolve(inputDir);
        logger.section("输入文件识别结果");
        logger.log("bootOrgTemplate=" + files.bootOrgTemplate);
        logger.log("bootDevice=" + files.bootDevice);
        logger.log("resourceOrgTemplate=" + files.resourceOrgTemplate);
        logger.log("resourceDevice=" + files.resourceDevice);
        logger.log("offsiteCatalog=" + files.offsiteCatalog);
        logger.log("跳过文件列表=" + files.skippedFiles);

        if (!files.missingFiles.isEmpty()) {
            throw new IllegalStateException("缺失必需文件: " + files.missingFiles);
        }

        ConfigLoader configLoader = new ConfigLoader();
        DeviceLoader deviceLoader = new DeviceLoader();
        Map<String, TerminalConfig> configs = configLoader.load(files.offsiteCatalog, logger);
        Map<String, DeviceRecord> resourceDevices = deviceLoader.load(files.resourceDevice, logger, "资源预警率设备表");
        Map<String, DeviceRecord> bootDevices = deviceLoader.load(files.bootDevice, logger, "开机率设备表");

        boolean resourceOk = writeResourceSummary(files, outputDir, logger, configs, resourceDevices);
        boolean bootOk = writeBootSummary(files, outputDir, logger, configs, bootDevices);

        logger.section("执行结果");
        logger.log("开机率汇总=" + (bootOk ? "成功" : "失败"));
        logger.log("资源预警率汇总=" + (resourceOk ? "成功" : "失败"));
        logger.writeTo(outputDir.resolve("汇总处理日志.txt"));

        if (!resourceOk || !bootOk) {
            throw new IllegalStateException("至少一张汇总表失败，请查看日志");
        }
    }

    private static boolean writeResourceSummary(InputFiles files, Path outputDir, ProcessingLogger logger,
                                                Map<String, TerminalConfig> configs,
                                                Map<String, DeviceRecord> resourceDevices) {
        try (Workbook wb = WorkbookFactory.create(files.resourceOrgTemplate.toFile())) {
            HeaderLocator.TemplateLayout layout = new HeaderLocator().locate(wb.getSheetAt(0), 5);
            logger.section("资源预警率模板解析");
            logger.log("headerRow=" + (layout.headerRowIndex() + 1) + ", dataStartRow=" + (layout.dataStartRowIndex() + 1));
            logger.log("columns=" + layout.columns());

            Map<String, Set<String>> aSet = new LinkedHashMap<>();
            Map<String, Set<String>> bSet = new LinkedHashMap<>();
            Map<String, Double> durationByBranch = new LinkedHashMap<>();

            for (DeviceRecord rec : resourceDevices.values()) {
                String branch = normalizeBranch(rec.branchName());
                if (branch.isBlank()) continue;
                aSet.computeIfAbsent(branch, k -> new LinkedHashSet<>()).add(rec.terminalId());
                durationByBranch.merge(branch, rec.resourceDuration() == null ? 0.0 : rec.resourceDuration(), Double::sum);
            }

            for (TerminalConfig cfg : configs.values()) {
                if (!cfg.inScope()) continue;
                String branch = normalizeBranch(displayBranch(cfg, null));
                bSet.computeIfAbsent(branch, k -> new LinkedHashSet<>()).add(cfg.terminalId());
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            Set<String> branches = new LinkedHashSet<>();
            branches.addAll(aSet.keySet());
            branches.addAll(bSet.keySet());
            logger.section("资源预警机构汇总摘要(前20)");
            int printCount = 0;
            for (String branch : branches) {
                Set<String> a = aSet.getOrDefault(branch, Set.of());
                Set<String> b = bSet.getOrDefault(branch, Set.of());
                Set<String> union = new LinkedHashSet<>(a);
                union.addAll(b);
                Set<String> bOnly = new LinkedHashSet<>(b);
                bOnly.removeAll(a);
                Map<String, Object> row = new HashMap<>();
                row.put("branch", branch);
                row.put("deviceTotal", union.size());
                row.put("alarmDuration", durationByBranch.getOrDefault(branch, 0.0));
                rows.add(row);

                if (printCount < 20) {
                    logger.log(String.format("%s A_count=%d B_only_count=%d total_count=%d sumDuration=%.2f", branch, a.size(), bOnly.size(), union.size(), durationByBranch.getOrDefault(branch, 0.0)));
                    printCount++;
                }
                if (a.size() + bOnly.size() != union.size()) {
                    logger.log("[自检失败] " + branch + " A + B_only != total");
                }
            }

            TemplateWriter writer = new TemplateWriter();
            Sheet sheet = wb.getSheetAt(0);
            writer.writeMetrics(sheet, layout, rows, Set.of("deviceTotal", "alarmDuration"));

            Path output = outputDir.resolve("资源预警率（机构）-汇总.xlsx");
            output.toFile().getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(output.toFile())) {
                wb.write(fos);
            }
            return true;
        } catch (Exception e) {
            logger.section("资源预警率汇总异常");
            logger.log(e.getMessage());
            return false;
        }
    }

    private static boolean writeBootSummary(InputFiles files, Path outputDir, ProcessingLogger logger,
                                            Map<String, TerminalConfig> configs,
                                            Map<String, DeviceRecord> bootDevices) {
        try (Workbook wb = WorkbookFactory.create(files.bootOrgTemplate.toFile())) {
            HeaderLocator.TemplateLayout layout = new HeaderLocator().locate(wb.getSheetAt(0), 5);
            logger.section("开机率模板解析");
            logger.log("headerRow=" + (layout.headerRowIndex() + 1) + ", dataStartRow=" + (layout.dataStartRowIndex() + 1));
            logger.log("columns=" + layout.columns());

            Map<String, Double> bootDurationByBranch = new LinkedHashMap<>();
            Map<String, Integer> terminalCount = new LinkedHashMap<>();
            for (DeviceRecord rec : bootDevices.values()) {
                String branch = normalizeBranch(rec.branchName());
                if (branch.isBlank()) continue;
                bootDurationByBranch.merge(branch, rec.resourceDuration() == null ? 0.0 : rec.resourceDuration(), Double::sum);
                terminalCount.merge(branch, 1, Integer::sum);
            }
            for (TerminalConfig cfg : configs.values()) {
                if (!cfg.inScope()) continue;
                String branch = normalizeBranch(displayBranch(cfg, null));
                terminalCount.putIfAbsent(branch, 0);
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            logger.section("开机率机构汇总摘要(前20)");
            int printCount = 0;
            for (String branch : terminalCount.keySet()) {
                Map<String, Object> row = new HashMap<>();
                row.put("branch", branch);
                row.put("bootDuration", bootDurationByBranch.getOrDefault(branch, 0.0));
                rows.add(row);
                if (printCount < 20) {
                    logger.log(String.format("%s terminalCount=%d bootDurationSum=%.2f", branch, terminalCount.get(branch), bootDurationByBranch.getOrDefault(branch, 0.0)));
                    printCount++;
                }
            }

            new TemplateWriter().writeMetrics(wb.getSheetAt(0), layout, rows, Set.of("bootDuration"));

            Path output = outputDir.resolve("开机率（机构）-汇总.xlsx");
            output.toFile().getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(output.toFile())) {
                wb.write(fos);
            }
            return true;
        } catch (Exception e) {
            logger.section("开机率汇总异常");
            logger.log(e.getMessage());
            return false;
        }
    }

    private static String displayBranch(TerminalConfig cfg, DeviceRecord rec) {
        if (cfg != null && cfg.outputBranchName() != null && !cfg.outputBranchName().isBlank()) return cfg.outputBranchName();
        if (cfg != null && cfg.branchName() != null && !cfg.branchName().isBlank()) return cfg.branchName();
        return rec == null ? "" : rec.branchName();
    }

    private static String normalizeBranch(String branch) {
        return branch == null ? "" : branch.trim();
    }
}