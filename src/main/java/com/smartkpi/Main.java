package com.smartkpi;

import com.smartkpi.core.*;
import com.smartkpi.io.*;
import com.smartkpi.model.*;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: java -jar smartkpi.jar <inputDir> <outputDir>");
        }

        Path inputDir = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        ProcessingLogger logger = new ProcessingLogger();

        InputResolver resolver = new InputResolver();
        InputFiles files = resolver.resolve(inputDir);

        logger.section("输入文件识别");
        logger.log("bootOrgTemplate=" + files.bootOrgTemplate);
        logger.log("bootDevice=" + files.bootDevice);
        logger.log("resourceOrgTemplate=" + files.resourceOrgTemplate);
        logger.log("resourceDevice=" + files.resourceDevice);
        logger.log("offsiteCatalog=" + files.offsiteCatalog);
        logger.log("跳过文件=" + files.skippedFiles);

        if (!files.missingFiles.isEmpty()) {
            throw new IllegalStateException("缺失必需文件: " + files.missingFiles);
        }

        ConfigLoader configLoader = new ConfigLoader();
        DeviceLoader deviceLoader = new DeviceLoader();
        MasterBuilder masterBuilder = new MasterBuilder();
        BranchAggregator aggregator = new BranchAggregator();

        Map<String, TerminalConfig> configs = configLoader.load(files.offsiteCatalog, logger);
        Map<String, DeviceRecord> resources = deviceLoader.load(files.resourceDevice, logger);

        List<TerminalUnified> unified = masterBuilder.build(configs, resources, logger);
        List<BranchMetric> metrics = aggregator.aggregateResource(unified, logger);

        try (Workbook wb = WorkbookFactory.create(files.resourceOrgTemplate.toFile())) {
            Sheet sheet = wb.getSheetAt(0);
            HeaderLocator locator = new HeaderLocator();
            Map<String, Integer> cols = locator.locateResourceColumns(sheet);
            if (!cols.containsKey("resourceRate")) {
                throw new IllegalStateException("模板资源预警率机构表 未识别 资源预警率 列");
            }
            logger.section("模板解析");
            logger.log("资源预警率 dataStart=row " + HeaderLocator.DATA_START);
            logger.log("columnIndexMap=" + cols);

            TemplateWriter writer = new TemplateWriter();
            writer.writeResourceMetrics(sheet, metrics, cols);

            Path output = outputDir.resolve("资源预警率（机构）- 汇总.xls");
            output.toFile().getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(output.toFile())) {
                wb.write(fos);
            }
        }

        logger.writeTo(outputDir.resolve("汇总处理日志.txt"));
    }
}