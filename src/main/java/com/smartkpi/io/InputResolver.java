package com.smartkpi.io;

import com.smartkpi.model.InputFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class InputResolver {
    public InputFiles resolve(Path inputDir) throws IOException {
        InputFiles inputFiles = new InputFiles();
        try (var stream = Files.list(inputDir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> mapFile(path, inputFiles));
        }
        inputFiles.validateRequired();
        return inputFiles;
    }

    private void mapFile(Path file, InputFiles resolved) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".xls") || name.endsWith(".xlsx"))) {
            resolved.skippedFiles.add(file.getFileName().toString());
            return;
        }

        if (name.contains("开机率") && name.contains("机构")) {
            resolved.bootOrgTemplate = chooseOrSkip(resolved.bootOrgTemplate, file, resolved);
        } else if (name.contains("开机率") && name.contains("设备")) {
            resolved.bootDevice = chooseOrSkip(resolved.bootDevice, file, resolved);
        } else if (name.contains("资源预警率") && name.contains("机构")) {
            resolved.resourceOrgTemplate = chooseOrSkip(resolved.resourceOrgTemplate, file, resolved);
        } else if (name.contains("资源预警率") && name.contains("设备")) {
            resolved.resourceDevice = chooseOrSkip(resolved.resourceDevice, file, resolved);
        } else if (name.contains("离行") && name.contains("目录")) {
            resolved.offsiteCatalog = chooseOrSkip(resolved.offsiteCatalog, file, resolved);
        } else {
            resolved.skippedFiles.add(file.getFileName().toString());
        }
    }

    private Path chooseOrSkip(Path existing, Path candidate, InputFiles resolved) {
        if (existing != null) {
            resolved.skippedFiles.add("ambiguous: " + candidate.getFileName());
            return existing;
        }
        return candidate;
    }
}