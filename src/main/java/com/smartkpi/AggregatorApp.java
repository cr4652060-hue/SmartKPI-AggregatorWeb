package com.smartkpi;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AggregatorApp {
    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            System.err.println("Usage(一行传参): <开机率设备.xlsx> <开机率机构模板.xlsx> <资源预警率设备.xlsx> <资源预警率机构模板.xlsx> <离行设备目录.xlsx> [outputDir]");
            System.exit(1);
        }

        Path bootDevice = Paths.get(args[0]);
        Path bootOrgTemplate = Paths.get(args[1]);
        Path resourceDevice = Paths.get(args[2]);
        Path resourceOrgTemplate = Paths.get(args[3]);
        Path offsiteCatalog = Paths.get(args[4]);
        Path outputDir = args.length == 6 ? Paths.get(args[5]) : Paths.get(".");

        System.out.println("[ARGS] 1=开机率设备: " + bootDevice.getFileName());
        System.out.println("[ARGS] 2=开机率机构模板: " + bootOrgTemplate.getFileName());
        System.out.println("[ARGS] 3=资源预警率设备: " + resourceDevice.getFileName());
        System.out.println("[ARGS] 4=资源预警率机构模板: " + resourceOrgTemplate.getFileName());
        System.out.println("[ARGS] 5=离行设备目录: " + offsiteCatalog.getFileName());
        System.out.println("[ARGS] 6=输出目录: " + outputDir.toAbsolutePath());

        AggregationService service = new AggregationService(outputDir);
        service.run(bootDevice, bootOrgTemplate, resourceDevice, resourceOrgTemplate, offsiteCatalog);
    }
}