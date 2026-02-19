package com.smartkpi.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class InputFiles {
    public Path bootOrgTemplate;
    public Path bootDevice;
    public Path resourceOrgTemplate;
    public Path resourceDevice;
    public Path offsiteCatalog;
    public final List<String> skippedFiles = new ArrayList<>();
    public final List<String> missingFiles = new ArrayList<>();

    public void validateRequired() {
        if (bootOrgTemplate == null) missingFiles.add("开机率（机构）模板");
        if (bootDevice == null) missingFiles.add("开机率（设备）");
        if (resourceOrgTemplate == null) missingFiles.add("资源预警率（机构）模板");
        if (resourceDevice == null) missingFiles.add("资源预警率（设备）");
        if (offsiteCatalog == null) missingFiles.add("离行设备目录");
    }
}