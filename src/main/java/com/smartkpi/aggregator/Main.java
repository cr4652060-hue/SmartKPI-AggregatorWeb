package com.smartkpi.aggregator;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.out.println("用法: java -jar smartkpi-aggregator.jar <输入目录> [输出目录]");
            System.exit(1);
        }
        Path input = Path.of(args[0]);
        Path output = args.length == 2 ? Path.of(args[1]) : input;
        Files.createDirectories(output);
        new ExcelProcessor().run(input, output);
        System.out.println("处理完成，输出目录: " + output.toAbsolutePath());
    }
}