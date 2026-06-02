package com.smartkpi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class SmartKpiController {

    private static final Logger log = LoggerFactory.getLogger(SmartKpiController.class);
    private static final String BOOT_OUTPUT_FILE_NAME = "开机率（机构）-汇总.xlsx";
    private static final String RESOURCE_OUTPUT_FILE_NAME = "资源预警率（机构）-汇总.xlsx";
    private static final MediaType EXCEL_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("[a-f0-9]{32}");

    private final Path baseWorkDir;

    public SmartKpiController(@Value("${smartkpi.work-dir:./smartkpi-work}") String workDir) {
        this.baseWorkDir = Paths.get(workDir).toAbsolutePath().normalize();
    }

    @PostMapping(
            value = "/aggregate",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<AggregateResponse> aggregate(
            @RequestParam(value = "bootDeviceFile", required = false) MultipartFile bootDeviceFile,
            @RequestParam(value = "bootOrgTemplateFile", required = false) MultipartFile bootOrgTemplateFile,
            @RequestParam(value = "resourceDeviceFile", required = false) MultipartFile resourceDeviceFile,
            @RequestParam(value = "resourceOrgTemplateFile", required = false) MultipartFile resourceOrgTemplateFile,
            @RequestParam(value = "offsiteCatalogFile", required = false) MultipartFile offsiteCatalogFile) {

        List<String> missingFiles = validateRequiredFiles(
                bootDeviceFile,
                bootOrgTemplateFile,
                resourceDeviceFile,
                resourceOrgTemplateFile,
                offsiteCatalogFile
        );
        if (!missingFiles.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    AggregateResponse.failure("缺少必传文件：" + String.join("、", missingFiles))
            );
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        Path taskDir = resolveTaskDir(taskId);
        Path inputDir = taskDir.resolve("input");
        Path outputDir = taskDir.resolve("output");

        try {
            Files.createDirectories(baseWorkDir);
            Files.createDirectories(inputDir);
            Files.createDirectories(outputDir);

            Path bootDevice = saveFile(bootDeviceFile, inputDir, "boot-device");
            Path bootOrgTemplate = saveFile(bootOrgTemplateFile, inputDir, "boot-org-template");
            Path resourceDevice = saveFile(resourceDeviceFile, inputDir, "resource-device");
            Path resourceOrgTemplate = saveFile(resourceOrgTemplateFile, inputDir, "resource-org-template");
            Path offsiteCatalog = saveFile(offsiteCatalogFile, inputDir, "offsite-catalog");

            log.info("Start aggregation, taskId={}, taskDir={}", taskId, taskDir);
            new AggregationService(outputDir).run(
                    bootDevice,
                    bootOrgTemplate,
                    resourceDevice,
                    resourceOrgTemplate,
                    offsiteCatalog
            );

            Path bootOutput = outputDir.resolve(BOOT_OUTPUT_FILE_NAME);
            Path resourceOutput = outputDir.resolve(RESOURCE_OUTPUT_FILE_NAME);
            if (!Files.exists(bootOutput) || !Files.exists(resourceOutput)) {
                throw new IllegalStateException("汇总已执行，但未找到输出文件，请检查模板内容或后台日志。");
            }

            return ResponseEntity.ok(AggregateResponse.success(
                    "汇总完成",
                    taskId,
                    buildDownloadUrl(taskId, "boot"),
                    buildDownloadUrl(taskId, "resource")
            ));
        } catch (IllegalStateException | IOException ex) {
            log.warn("Aggregation failed, taskId={}, message={}", taskId, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    AggregateResponse.failure(resolveErrorMessage(ex))
            );
        } catch (Exception ex) {
            log.error("Unexpected aggregation error, taskId={}", taskId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    AggregateResponse.failure(resolveErrorMessage(ex))
            );
        }
    }

    @GetMapping("/download/{taskId}/{type}")
    public ResponseEntity<Resource> download(@PathVariable String taskId, @PathVariable String type) throws IOException {
        Path taskDir = resolveTaskDir(taskId);
        Path outputDir = taskDir.resolve("output");

        String fileName = switch (type.toLowerCase(Locale.ROOT)) {
            case "boot" -> BOOT_OUTPUT_FILE_NAME;
            case "resource" -> RESOURCE_OUTPUT_FILE_NAME;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "下载类型不支持：" + type);
        };

        Path file = outputDir.resolve(fileName).normalize();
        if (!file.startsWith(outputDir) || !Files.exists(file) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到可下载文件，请先重新汇总。");
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(EXCEL_MEDIA_TYPE)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file));
    }

    private List<String> validateRequiredFiles(MultipartFile bootDeviceFile,
                                               MultipartFile bootOrgTemplateFile,
                                               MultipartFile resourceDeviceFile,
                                               MultipartFile resourceOrgTemplateFile,
                                               MultipartFile offsiteCatalogFile) {
        List<String> missingFiles = new ArrayList<>();
        if (isMissing(bootDeviceFile)) {
            missingFiles.add("开机率设备表");
        }
        if (isMissing(bootOrgTemplateFile)) {
            missingFiles.add("开机率机构模板");
        }
        if (isMissing(resourceDeviceFile)) {
            missingFiles.add("资源预警率设备表");
        }
        if (isMissing(resourceOrgTemplateFile)) {
            missingFiles.add("资源预警率机构模板");
        }
        if (isMissing(offsiteCatalogFile)) {
            missingFiles.add("离行设备目录");
        }
        return missingFiles;
    }

    private boolean isMissing(MultipartFile file) {
        return file == null || file.isEmpty();
    }

    private Path saveFile(MultipartFile file, Path directory, String baseName) throws IOException {
        String extension = extractExtension(file.getOriginalFilename());
        Path target = directory.resolve(baseName + extension).normalize();
        if (!target.startsWith(directory)) {
            throw new IOException("文件保存路径非法：" + baseName);
        }

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private String extractExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return ".xlsx";
        }

        String cleanName = Paths.get(fileName).getFileName().toString();
        int extensionIndex = cleanName.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == cleanName.length() - 1) {
            return ".xlsx";
        }
        return cleanName.substring(extensionIndex);
    }

    private Path resolveTaskDir(String taskId) {
        if (!TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务编号格式非法。");
        }

        Path taskDir = baseWorkDir.resolve(taskId).normalize();
        if (!taskDir.startsWith(baseWorkDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务目录非法。");
        }
        return taskDir;
    }

    private String buildDownloadUrl(String taskId, String type) {
        return "/api/download/" + taskId + "/" + type;
    }

    private String resolveErrorMessage(Throwable throwable) {
        String fallback = "处理失败，请检查上传的 Excel 文件是否完整且表头可识别。";
        Throwable current = throwable;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                fallback = current.getMessage().trim();
            }
            current = current.getCause();
        }
        return fallback;
    }

    public record AggregateResponse(
            boolean success,
            String message,
            String taskId,
            String bootDownloadUrl,
            String resourceDownloadUrl) {

        static AggregateResponse success(String message, String taskId, String bootDownloadUrl, String resourceDownloadUrl) {
            return new AggregateResponse(true, message, taskId, bootDownloadUrl, resourceDownloadUrl);
        }

        static AggregateResponse failure(String message) {
            return new AggregateResponse(false, message, null, null, null);
        }
    }
}
