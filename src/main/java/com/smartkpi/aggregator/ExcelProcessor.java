package com.smartkpi.aggregator;

import com.smartkpi.aggregator.Domain.DepartConfig;
import com.smartkpi.aggregator.Domain.DeviceRate;
import com.smartkpi.aggregator.Domain.MetricType;
import com.smartkpi.aggregator.Domain.OutputRow;
import com.smartkpi.aggregator.Domain.ProcessingLog;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ExcelProcessor {
    private final AggregationService aggregationService = new AggregationService();

    public void run(Path inputDir, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        InputFiles files = resolveInputFiles(inputDir);
        if (!files.missingCoreFiles().isEmpty()) {
            throw new IllegalArgumentException("缺少核心输入文件: " + files.missingCoreFiles());
        }

        ConfigData configData = readConfig(files.departCatalog());
        Set<String> canceled = aggregationService.normalizeSet(configData.canceledBranches);

        ProcessingLog bootLog = new ProcessingLog();
        bootLog.skippedFiles.addAll(files.skippedFiles());
        List<DeviceRate> bootRates = readDeviceRates(files.bootDevice(), MetricType.BOOT, bootLog);
        List<OutputRow> bootRows = aggregationService.buildRows(bootRates, configData.departMap, canceled, MetricType.BOOT, bootLog);
        Path bootOut = outputDir.resolve("开机率（机构）- 汇总.xls");
        writeSummary(files.bootOrg(), bootOut, bootRows, MetricType.BOOT, bootLog);

        ProcessingLog resourceLog = new ProcessingLog();
        resourceLog.skippedFiles.addAll(files.skippedFiles());
        List<DeviceRate> resourceRates = readDeviceRates(files.resourceDevice(), MetricType.RESOURCE, resourceLog);
        List<OutputRow> resourceRows = aggregationService.buildRows(resourceRates, configData.departMap, canceled, MetricType.RESOURCE, resourceLog);
        Path resourceOut = outputDir.resolve("资源预警率（机构）- 汇总.xls");
        writeSummary(files.resourceOrg(), resourceOut, resourceRows, MetricType.RESOURCE, resourceLog);

        writeLog(outputDir.resolve("汇总处理日志.txt"), bootLog, resourceLog);
    }

    private InputFiles resolveInputFiles(Path inputDir) throws IOException {
        Map<String, Path> found = new HashMap<>();
        List<String> skipped = new ArrayList<>();

        try (var stream = Files.list(inputDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".xml")) {
                    skipped.add("跳过XML文件: " + name);
                    return;
                }
                if (!(lower.endsWith(".xls") || lower.endsWith(".xlsx"))) {
                    return;
                }
                String norm = normalizeFileName(name);
                if (norm.contains("开机率") && norm.contains("机构")) {
                    found.putIfAbsent("bootOrg", path);
                } else if (norm.contains("开机率") && norm.contains("设备")) {
                    found.putIfAbsent("bootDevice", path);
                } else if (norm.contains("资源预警率") && norm.contains("机构")) {
                    found.putIfAbsent("resourceOrg", path);
                } else if (norm.contains("资源预警率") && norm.contains("设备")) {
                    found.putIfAbsent("resourceDevice", path);
                } else if (norm.contains("离行设备目录")) {
                    found.putIfAbsent("departCatalog", path);
                }
            });
        }

        List<String> missing = new ArrayList<>();
        if (!found.containsKey("bootOrg")) missing.add("开机率（机构）.*");
        if (!found.containsKey("bootDevice")) missing.add("开机率（设备）.*");
        if (!found.containsKey("resourceOrg")) missing.add("资源预警率（机构）.*");
        if (!found.containsKey("resourceDevice")) missing.add("资源预警率（设备）.*");
        if (!found.containsKey("departCatalog")) missing.add("离行设备目录.*");

        return new InputFiles(found.get("bootOrg"), found.get("bootDevice"), found.get("resourceOrg"), found.get("resourceDevice"), found.get("departCatalog"), missing, skipped);
    }

    private String normalizeFileName(String name) {
        return name.replace('（', '(').replace('）', ')').replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private Workbook openWorkbook(Path path, ProcessingLog log) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return WorkbookFactory.create(is);
        } catch (Exception ex) {
            if (log != null) {
                log.addSkippedFile("无法打开文件: " + path.getFileName() + "，原因=" + ex.getMessage());
            }
            throw new IOException("无法打开文件: " + path, ex);
        }
    }

    private void writeLog(Path path, ProcessingLog boot, ProcessingLog resource) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("运行时间: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        appendSection(sb, "输入跳过/异常文件", merge(boot.skippedFiles, resource.skippedFiles));
        appendSection(sb, "开机率分支汇总", boot.branchSummaries);
        appendSection(sb, "资源预警率分支汇总", resource.branchSummaries);
        appendSection(sb, "离行目录匹配统计", merge(boot.terminalMatchStats, resource.terminalMatchStats));
        appendSection(sb, "离行目录存在但设备表找不到", merge(boot.missingInDevice, resource.missingInDevice));
        appendSection(sb, "设备表存在但离行目录缺失", merge(boot.missingInConfig, resource.missingInConfig));
        appendSection(sb, "资源预警缺失按默认0处理", resource.defaultResourceZero);
        appendSection(sb, "纳入口径集合为空网点", merge(boot.emptyBranchScope, resource.emptyBranchScope));
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private List<String> merge(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<>(a);
        set.addAll(b);
        return new ArrayList<>(set);
    }

    private void appendSection(StringBuilder sb, String title, List<String> lines) {
        sb.append("\n== ").append(title).append(" ==\n");
        if (lines.isEmpty()) {
            sb.append("(无)\n");
            return;
        }
        for (String line : lines) {
            sb.append("- ").append(line).append('\n');
        }
    }

    private ConfigData readConfig(Path departCatalog) throws IOException {
        try (Workbook wb = openWorkbook(departCatalog, null)) {
            Sheet sheet1 = wb.getSheetAt(0);
            Sheet sheet2 = wb.getSheetAt(1);
            int header = detectHeaderRow(sheet1, List.of("终端编号", "是否纳入本网点接管", "是否纳入网点均值", "所属网点", "直接复制"));
            Map<String, Integer> idx = headerIndex(sheet1, sheet1.getRow(header));
            int branchIdx = findColumn(idx, List.of("所属网点", "所属机构"));
            int terminalIdx = findColumn(idx, List.of("终端编号", "设备编号"));
            Integer manageIdx = findOptionalColumn(idx, List.of("是否纳入本网点接管", "是否纳入口径"));
            Integer includeAvgIdx = findOptionalColumn(idx, List.of("是否纳入网点均值"));
            int outNameIdx = findColumn(idx, List.of("直接复制"));
            Integer bootFixIdx = findOptionalColumn(idx, List.of("开机率固定值"));
            Integer resourceFixIdx = findOptionalColumn(idx, List.of("资源预警率固定值"));

            Map<String, DepartConfig> departMap = new HashMap<>();
            for (int r = header + 1; r <= sheet1.getLastRowNum(); r++) {
                Row row = sheet1.getRow(r);
                if (row == null) continue;
                String terminal = Domain.normalizeTerminalId(cell(row, terminalIdx));
                if (Domain.blank(terminal)) continue;
                String branch = Domain.normalizeName(cell(row, branchIdx));
                String manageVal = manageIdx == null ? "Y" : cell(row, manageIdx);
                String includeVal = includeAvgIdx == null ? manageVal : cell(row, includeAvgIdx);
                String outName = cell(row, outNameIdx);
                Double bootFixed = bootFixIdx == null ? null : parseRate(cell(row, bootFixIdx));
                Double resourceFixed = resourceFixIdx == null ? null : parseRate(cell(row, resourceFixIdx));
                departMap.put(terminal, new DepartConfig(
                        terminal,
                        branch,
                        !"N".equalsIgnoreCase(manageVal.trim()),
                        !"N".equalsIgnoreCase(includeVal.trim()),
                        outName,
                        bootFixed,
                        resourceFixed));
            }

            int cancelHeader = detectHeaderRow(sheet2, List.of("网点"));
            Set<String> canceled = new HashSet<>();
            for (int r = cancelHeader + 1; r <= sheet2.getLastRowNum(); r++) {
                Row row = sheet2.getRow(r);
                if (row == null) continue;
                String name = cell(row, 0);
                if (!Domain.blank(name)) {
                    canceled.add(name);
                }
            }
            return new ConfigData(departMap, canceled);
        }
    }

    private List<DeviceRate> readDeviceRates(Path path, MetricType type, ProcessingLog log) throws IOException {
        try (Workbook wb = openWorkbook(path, log)) {
            Sheet sheet = wb.getSheetAt(0);
            int header = detectHeaderRow(sheet, List.of("终端", "设备编号", "所属网", "开机率", "资源预警率"));
            Map<String, Integer> idx = headerIndex(sheet, sheet.getRow(header));
            int terminalIdx = findColumn(idx, List.of("终端编号", "设备编号"));
            int branchIdx = findColumn(idx, List.of("所属网点", "所属机构"));
            int rateIdx = type == MetricType.BOOT ? findColumn(idx, List.of("开机率")) : findColumn(idx, List.of("资源预警率"));

            List<DeviceRate> rates = new ArrayList<>();
            for (int r = header + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String terminal = Domain.normalizeTerminalId(cell(row, terminalIdx));
                if (terminal.isEmpty()) continue;
                String branch = Domain.normalizeName(cell(row, branchIdx));
                Double rate = parseRate(cell(row, rateIdx));
                rates.add(new DeviceRate(terminal, branch, rate));
            }
            return rates;
        }
    }

    private Double parseRate(String raw) {
        return RateParser.parseString(raw).stream().boxed().findFirst().orElse(null);
    }

    private void writeSummary(Path template, Path output, List<OutputRow> rows, MetricType type, ProcessingLog log) throws IOException {
        try (Workbook wb = openWorkbook(template, log)) {
            Sheet sheet = wb.getSheetAt(0);
            if (type == MetricType.BOOT) {
                removeColumns(sheet, new int[]{8, 7});
            } else {
                removeColumns(sheet, new int[]{5, 4, 3, 2});
            }

            int headerRowNum = detectHeaderRow(sheet, List.of("所属机构", "所属网点", "网点", "机构名称", "开机率", "资源预警率", "序号"));
            Row headerRow = sheet.getRow(headerRowNum);
            Map<String, Integer> idx = headerIndex(sheet, headerRow);
            int nameIdx = findColumn(idx, List.of("所属机构", "所属网点", "网点", "机构名称"));
            int rateIdx = type == MetricType.BOOT ? findColumn(idx, List.of("开机率")) : findColumn(idx, List.of("资源预警率", "率"));
            int seqIdx = ensureSequenceColumn(sheet, headerRowNum, nameIdx);
            if (seqIdx <= nameIdx) {
                nameIdx++;
                rateIdx++;
            }
            int dataStart = headerRowNum + 1;
            Map<String, RowSnapshot> rowCache = cacheOriginalRows(sheet, dataStart, nameIdx);

            CellStyle rateStyle = null;
            if (sheet.getRow(dataStart) != null && sheet.getRow(dataStart).getCell(rateIdx) != null) {
                rateStyle = sheet.getRow(dataStart).getCell(rateIdx).getCellStyle();
            }

            for (int r = dataStart; r <= Math.max(sheet.getLastRowNum(), dataStart + rows.size() + 5); r++) {
                Row row = sheet.getRow(r);
                if (row != null) {
                    clearRow(row);
                }
            }

            for (int i = 0; i < rows.size(); i++) {
                OutputRow rec = rows.get(i);
                Row row = sheet.getRow(dataStart + i);
                if (row == null) row = sheet.createRow(dataStart + i);

                if (rec.branchRow() && rec.preserveMiddleColumns()) {
                    RowSnapshot snapshot = rowCache.get(Domain.normalizeName(rec.sourceBranchName()));
                    if (snapshot != null) {
                        restoreRow(row, snapshot);
                    } else {
                        clearRow(row);
                    }
                } else {
                    clearRow(row);
                }

                writeCell(row, seqIdx, rec.seq());
                writeCell(row, nameIdx, rec.name());
                if (rec.rate() == null) {
                    writeCell(row, rateIdx, "-");
                } else {
                    Cell cell = row.getCell(rateIdx);
                    if (cell == null) cell = row.createCell(rateIdx);
                    cell.setCellValue(RateParser.round2(rec.rate()));
                    if (rateStyle != null) {
                        cell.setCellStyle(rateStyle);
                    }
                }
            }

            try (OutputStream os = Files.newOutputStream(output)) {
                wb.write(os);
            }
        }
    }

    private Map<String, RowSnapshot> cacheOriginalRows(Sheet sheet, int dataStart, int nameIdx) {
        Map<String, RowSnapshot> cache = new HashMap<>();
        for (int r = dataStart; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String name = Domain.normalizeName(cell(row, nameIdx));
            if (name.isEmpty()) {
                continue;
            }
            cache.putIfAbsent(name, RowSnapshot.capture(row));
        }
        return cache;
    }

    private void clearRow(Row row) {
        for (int c = 0; c <= row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null) {
                cell.setBlank();
            }
        }
    }

    private void restoreRow(Row target, RowSnapshot snapshot) {
        for (CellSnapshot cell : snapshot.cells()) {
            Cell dst = target.getCell(cell.index());
            if (dst == null) {
                dst = target.createCell(cell.index());
            }
            cell.applyTo(dst);
        }
    }

    private void writeCell(Row row, int index, int value) {
        Cell cell = row.getCell(index);
        if (cell == null) cell = row.createCell(index);
        cell.setCellValue(value);
    }

    private void writeCell(Row row, int index, String value) {
        Cell cell = row.getCell(index);
        if (cell == null) cell = row.createCell(index);
        cell.setCellValue(value);
    }

    private int ensureSequenceColumn(Sheet sheet, int headerRowNum, int suggestedIndex) {
        Row headerRow = sheet.getRow(headerRowNum);
        if (headerRow == null) {
            headerRow = sheet.createRow(headerRowNum);
        }
        Map<String, Integer> idx = headerIndex(sheet, headerRow);
        Integer existing = idx.get("序号");
        if (existing != null) {
            return existing;
        }

        int insertAt = Math.max(suggestedIndex, 0);
        insertColumn(sheet, insertAt);
        Cell seqHeader = headerRow.getCell(insertAt);
        if (seqHeader == null) {
            seqHeader = headerRow.createCell(insertAt);
        }
        seqHeader.setCellValue("序号");
        return insertAt;
    }

    private void insertColumn(Sheet sheet, int insertAt) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int last = row.getLastCellNum();
            if (last < 0) {
                continue;
            }
            for (int c = last - 1; c >= insertAt; c--) {
                Cell src = row.getCell(c);
                Cell dst = row.getCell(c + 1);
                if (src == null) {
                    if (dst != null) {
                        dst.setBlank();
                    }
                    continue;
                }
                if (dst == null) {
                    dst = row.createCell(c + 1);
                }
                copyCell(src, dst);
                src.setBlank();
            }
        }
        shiftMergedRegionsForInsert(sheet, insertAt);
    }

    private void shiftMergedRegionsForInsert(Sheet sheet, int insertAt) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress range = sheet.getMergedRegion(i);
            if (range.getFirstColumn() >= insertAt) {
                range.setFirstColumn(range.getFirstColumn() + 1);
                range.setLastColumn(range.getLastColumn() + 1);
            } else if (range.getLastColumn() >= insertAt) {
                range.setLastColumn(range.getLastColumn() + 1);
            }
        }
    }

    private void removeColumns(Sheet sheet, int[] sortedDescColumnIdx) {
        for (int colIdx : sortedDescColumnIdx) {
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                int last = row.getLastCellNum();
                if (last < 0 || colIdx >= last) continue;
                for (int c = colIdx; c < last - 1; c++) {
                    Cell src = row.getCell(c + 1);
                    Cell dst = row.getCell(c);
                    if (dst == null) dst = row.createCell(c);
                    if (src == null) {
                        dst.setBlank();
                    } else {
                        copyCell(src, dst);
                    }
                }
                Cell tail = row.getCell(last - 1);
                if (tail != null) {
                    row.removeCell(tail);
                }
            }
            shiftMergedRegions(sheet, colIdx);
        }
    }

    private void shiftMergedRegions(Sheet sheet, int deletedCol) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress range = sheet.getMergedRegion(i);
            if (range.getFirstColumn() > deletedCol) {
                range.setFirstColumn(range.getFirstColumn() - 1);
                range.setLastColumn(range.getLastColumn() - 1);
            } else if (range.getLastColumn() >= deletedCol) {
                range.setLastColumn(Math.max(range.getFirstColumn(), range.getLastColumn() - 1));
            }
        }
    }

    private void copyCell(Cell src, Cell dst) {
        dst.setCellStyle(src.getCellStyle());
        switch (src.getCellType()) {
            case STRING -> dst.setCellValue(src.getStringCellValue());
            case NUMERIC -> dst.setCellValue(src.getNumericCellValue());
            case BOOLEAN -> dst.setCellValue(src.getBooleanCellValue());
            case FORMULA -> dst.setCellFormula(src.getCellFormula());
            case BLANK -> dst.setBlank();
            default -> dst.setCellValue(new DataFormatter().formatCellValue(src));
        }
    }

    private int detectHeaderRow(Sheet sheet, List<String> candidates) {
        int scanLimit = Math.min(sheet.getLastRowNum(), 30);
        int bestRow = -1;
        int bestScore = -1;
        for (int r = 0; r <= scanLimit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String joined = rowToText(row);
            int score = 0;
            for (String key : candidates) {
                if (joined.contains(key)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestRow = r;
            }
        }
        if (bestRow >= 0 && bestScore > 0) {
            return bestRow;
        }
        throw new IllegalArgumentException("无法识别表头: " + sheet.getSheetName());
    }

    private String rowToText(Row row) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c <= row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null) {
                sb.append(new DataFormatter().formatCellValue(cell)).append('|');
            }
        }
        return sb.toString();
    }

    private Map<String, Integer> headerIndex(Sheet sheet, Row row) {
        Map<String, Integer> idx = new HashMap<>();
        if (row == null) {
            return idx;
        }
        for (int c = 0; c <= row.getLastCellNum(); c++) {
            String title = Domain.normalizeName(headerCell(sheet, row.getRowNum(), c));
            if (!title.isEmpty()) {
                idx.put(title, c);
            }
        }
        return idx;
    }

    private String headerCell(Sheet sheet, int rowIndex, int colIndex) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            return "";
        }
        String direct = cell(row, colIndex);
        if (!Domain.blank(direct)) {
            return direct;
        }
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            if (range.isInRange(rowIndex, colIndex)) {
                Row firstRow = sheet.getRow(range.getFirstRow());
                if (firstRow == null) {
                    return "";
                }
                return cell(firstRow, range.getFirstColumn());
            }
        }
        return "";
    }

    private Integer findOptionalColumn(Map<String, Integer> idx, List<String> keywords) {
        try {
            return findColumn(idx, keywords);
        } catch (Exception e) {
            return null;
        }
    }

    private int findColumn(Map<String, Integer> idx, List<String> keywords) {
        for (Map.Entry<String, Integer> entry : idx.entrySet()) {
            for (String key : keywords) {
                if (entry.getKey().contains(key)) {
                    return entry.getValue();
                }
            }
        }
        throw new IllegalArgumentException("未找到列: " + keywords + "，可用列=" + idx.keySet());
    }

    private String cell(Row row, int index) {
        Cell cell = row.getCell(index);
        return cell == null ? "" : new DataFormatter().formatCellValue(cell).trim();
    }

    private record ConfigData(Map<String, DepartConfig> departMap, Set<String> canceledBranches) {
    }

    private record InputFiles(Path bootOrg, Path bootDevice, Path resourceOrg, Path resourceDevice,
                              Path departCatalog, List<String> missingCoreFiles, List<String> skippedFiles) {
    }

    private record RowSnapshot(List<CellSnapshot> cells) {
        static RowSnapshot capture(Row row) {
            List<CellSnapshot> cells = new ArrayList<>();
            for (int c = 0; c <= row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                if (cell != null) {
                    cells.add(CellSnapshot.of(c, cell));
                }
            }
            return new RowSnapshot(cells);
        }
    }

    private record CellSnapshot(int index, CellType type, String textValue, Double numValue, Boolean boolValue,
                                String formula, CellStyle style) {
        static CellSnapshot of(int index, Cell cell) {
            return switch (cell.getCellType()) {
                case STRING -> new CellSnapshot(index, CellType.STRING, cell.getStringCellValue(), null, null, null, cell.getCellStyle());
                case NUMERIC -> new CellSnapshot(index, CellType.NUMERIC, null, cell.getNumericCellValue(), null, null, cell.getCellStyle());
                case BOOLEAN -> new CellSnapshot(index, CellType.BOOLEAN, null, null, cell.getBooleanCellValue(), null, cell.getCellStyle());
                case FORMULA -> new CellSnapshot(index, CellType.FORMULA, null, null, null, cell.getCellFormula(), cell.getCellStyle());
                default -> new CellSnapshot(index, CellType.BLANK, null, null, null, null, cell.getCellStyle());
            };
        }

        void applyTo(Cell cell) {
            if (style != null) {
                cell.setCellStyle(style);
            }
            switch (type) {
                case STRING -> cell.setCellValue(textValue == null ? "" : textValue);
                case NUMERIC -> cell.setCellValue(numValue == null ? 0 : numValue);
                case BOOLEAN -> cell.setCellValue(Boolean.TRUE.equals(boolValue));
                case FORMULA -> cell.setCellFormula(formula == null ? "" : formula);
                default -> cell.setBlank();
            }
        }
    }
}