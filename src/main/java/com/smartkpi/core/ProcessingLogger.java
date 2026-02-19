package com.smartkpi.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ProcessingLogger {
    private final List<String> lines = new ArrayList<>();

    public void section(String title) {
        lines.add("== " + title + " ==");
    }

    public void log(String line) {
        lines.add(line);
    }

    public List<String> snapshot() {
        return List.copyOf(lines);
    }

    public void writeTo(Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, lines, StandardCharsets.UTF_8);
    }
}