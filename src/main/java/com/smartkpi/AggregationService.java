package com.smartkpi;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SmartKPI-Aggregator (Java 17, Apache POI XSSF)
 *
 * 本次新增规则（仅影响“离行设备单列行”）：
 * - 若离行目录中“开机率固定值（%）/资源预警率固定值（%）”有值，则无条件使用固定值；
 *   不再去设备表查终端数据（避免乡镇离行设备被设备表值覆盖）。
 *
 * 其它已正确规则保持不动：
 * - 开机率降序、资源预警率升序
 * - 四台离行设备加入并参与最终排序
 * - 最终排序后写序号列
 * - 去掉%号，0.93% 不会再变 93
 */
public class AggregationService {

    private final Path outputDir;

    public AggregationService(Path outputDir) {
        this.outputDir = outputDir;
    }

    public void run(Path bootDeviceFile,
                    Path bootOrgTemplateFile,
                    Path resourceDeviceFile,
                    Path resourceOrgTemplateFile,
                    Path offsiteCatalogFile) throws IOException {

        System.out.println("[INFO] outputDir=" + outputDir.toAbsolutePath());

        OffsiteData offsite = loadOffsiteData(offsiteCatalogFile);

        DeviceTable bootDevice = loadBootDeviceTable(bootDeviceFile);
        DeviceTable resourceDevice = loadResourceDeviceTable(resourceDeviceFile);

        Path bootOut = outputDir.resolve("开机率（机构）-汇总.xlsx");
        Path resOut  = outputDir.resolve("资源预警率（机构）-汇总.xlsx");

        aggregateBootOrg(bootOrgTemplateFile, bootOut, bootDevice, offsite);
        aggregateResourceOrg(resourceOrgTemplateFile, resOut, bootDevice, resourceDevice, offsite);

        System.out.println("[DONE] " + bootOut.toAbsolutePath());
        System.out.println("[DONE] " + resOut.toAbsolutePath());
    }

    // =====================================================================================
    // 1) 开机率（机构）
    // =====================================================================================

    private void aggregateBootOrg(Path template, Path out,
                                  DeviceTable bootDevice,
                                  OffsiteData offsite) throws IOException {

        try (InputStream in = Files.newInputStream(template);
             Workbook wb = new XSSFWorkbook(in)) {

            Sheet sheet = wb.getSheetAt(0);
            int dataStart = 4; // 第5行 index=4

            HeaderMap hm = detectOrgHeader(sheet,
                    new String[]{"所属机构", "所属网点", "机构", "网点"},
                    new String[]{"开机率", "开机率(%)", "开机率%"}
            );

            ensureSeqColumnExistsSafe(sheet, hm);
            stripPercentInHeader(sheet, hm.rateHeaderRow, hm.rateCol);

            List<RowRec> baseRows = readOrgRows(sheet, dataStart, hm, offsite);

            Map<String, Integer> offsiteCount = offsite.excludedOffsiteCountByBranch();

            Map<String, List<String>> inScopeTerminalsByBranch = new HashMap<>();
            for (Map.Entry<String, List<String>> e : bootDevice.terminalsByBranch.entrySet()) {
                String b = e.getKey();
                List<String> terminals = e.getValue();
                Set<String> excluded = offsite.excludedTerminalSetForBranch(b);
                List<String> inScope = terminals.stream()
                        .filter(t -> !excluded.contains(t))
                        .collect(Collectors.toList());
                inScopeTerminalsByBranch.put(b, inScope);
            }

            // 网点行重算/规范化
            for (RowRec rr : baseRows) {
                String b = rr.branchNorm;
                int exCnt = offsiteCount.getOrDefault(b, 0);
                if (exCnt <= 0) {
                    rr.rate = normalizeRateValue(rr.rate, rr.rateShownAsPercent);
                    continue;
                }

                List<String> inScopeTerms = inScopeTerminalsByBranch.getOrDefault(b, Collections.emptyList());
                int denom = inScopeTerms.size();

                if (denom <= 0) {
                    rr.rate = 100.0;
                } else {
                    double sum = 0;
                    for (String t : inScopeTerms) {
                        ParsedRate pr = bootDevice.rateByTerminal.get(t);
                        double v = (pr == null || pr.value == null) ? 100.0 : normalizeRateValue(pr.value, pr.shownAsPercent);
                        sum += v;
                    }
                    rr.rate = sum / denom;
                }
                rr.blankMiddle = true;
            }

            // 离行设备单列行（四台都加）—— ✅ 新规则：固定值优先，不查设备表
            List<RowRec> offsiteRows = new ArrayList<>();
            for (OffsiteRecord rec : offsite.records) {
                if (!rec.includeInBranchScope && !rec.includeInBranchAverage) {
                    RowRec rr = RowRec.newOffsiteStandalone(rec.displayName);
                    rr.blankMiddle = true;

                    // ✅ 固定值优先（乡镇离行设备有固定值就用固定值）
                    Double raw;
                    boolean shownPct;

                    if (rec.fixedBootRate != null) {
                        raw = rec.fixedBootRate;
                        shownPct = true; // 固定值是百分数口径
                    } else {
                        ParsedRate pr = bootDevice.rateByTerminal.get(rec.terminalIdNorm);
                        raw = (pr == null ? null : pr.value);
                        shownPct = (pr != null && pr.shownAsPercent);

                        if (raw == null) {
                            raw = 100.0;      // 默认开机率 100
                            shownPct = true;
                        }
                    }

                    rr.rate = normalizeRateValue(raw, shownPct);
                    offsiteRows.add(rr);
                }
            }

            List<RowRec> all = new ArrayList<>(baseRows.size() + offsiteRows.size());
            all.addAll(baseRows);
            all.addAll(offsiteRows);

            // 开机率降序
            all.sort((a, b) -> Double.compare(
                    (b.rate == null ? -1e18 : b.rate),
                    (a.rate == null ? -1e18 : a.rate)
            ));

            // 最终排序后写序号
            for (int i = 0; i < all.size(); i++) all.get(i).seq = i + 1;

            writeRowsBack(wb, sheet, dataStart, hm, all, false);

            try (OutputStream os = Files.newOutputStream(out)) {
                wb.write(os);
            }
        }
    }

    // =====================================================================================
    // 2) 资源预警率（机构）
    // =====================================================================================

    private void aggregateResourceOrg(Path template, Path out,
                                      DeviceTable bootDevice,
                                      DeviceTable resourceDevice,
                                      OffsiteData offsite) throws IOException {

        try (InputStream in = Files.newInputStream(template);
             Workbook wb = new XSSFWorkbook(in)) {

            Sheet sheet = wb.getSheetAt(0);
            int dataStart = 4;

            HeaderMap hm = detectOrgHeader(sheet,
                    new String[]{"所属网点", "所属机构", "机构", "网点"},
                    new String[]{"资源预警率", "资源预警率(%)", "资源预警率%"}
            );

            ensureSeqColumnExistsSafe(sheet, hm);
            stripPercentInHeader(sheet, hm.rateHeaderRow, hm.rateCol);

            hm.deviceTotalCol = findColContainsAny(sheet, hm.headerRow, new String[]{"设备总台数", "设备数量", "自助设备数量"});

            List<RowRec> baseRows = readOrgRows(sheet, dataStart, hm, offsite);

            Map<String, Integer> offsiteCount = offsite.excludedOffsiteCountByBranch();
            Map<String, Integer> deviceTotal = bootDevice.deviceTotalByBranch;

            Map<String, List<String>> inScopeTerminalsByBranch = new HashMap<>();
            for (Map.Entry<String, List<String>> e : bootDevice.terminalsByBranch.entrySet()) {
                String b = e.getKey();
                List<String> terminals = e.getValue();
                Set<String> excluded = offsite.excludedTerminalSetForBranch(b);
                List<String> inScope = terminals.stream()
                        .filter(t -> !excluded.contains(t))
                        .collect(Collectors.toList());
                inScopeTerminalsByBranch.put(b, inScope);
            }

            // 网点行重算/规范化
            for (RowRec rr : baseRows) {
                String b = rr.branchNorm;
                rr.deviceTotal = deviceTotal.getOrDefault(b, null);

                int exCnt = offsiteCount.getOrDefault(b, 0);
                if (exCnt <= 0) {
                    rr.rate = normalizeRateValue(rr.rate, rr.rateShownAsPercent);
                    continue;
                }

                List<String> inScopeTerms = inScopeTerminalsByBranch.getOrDefault(b, Collections.emptyList());
                int denom = inScopeTerms.size();

                if (denom <= 0) {
                    rr.rate = 0.0;
                } else {
                    double sum = 0;
                    for (String t : inScopeTerms) {
                        ParsedRate pr = resourceDevice.rateByTerminal.get(t);
                        double raw = (pr == null || pr.value == null) ? 0.0 : pr.value; // 缺失终端默认0
                        boolean shownPct = (pr != null && pr.shownAsPercent);
                        sum += normalizeRateValue(raw, shownPct);
                    }
                    rr.rate = sum / denom;
                }
                rr.blankMiddle = true;
            }

            // 离行设备单列行（四台都加）—— ✅ 新规则：固定值优先，不查设备表
            List<RowRec> offsiteRows = new ArrayList<>();
            for (OffsiteRecord rec : offsite.records) {
                if (!rec.includeInBranchScope && !rec.includeInBranchAverage) {
                    RowRec rr = RowRec.newOffsiteStandalone(rec.displayName);
                    rr.blankMiddle = true;

                    Double raw;
                    boolean shownPct;

                    // ✅ 固定值优先：有固定值就用固定值（0/100 等）
                    if (rec.fixedResourceRate != null) {
                        raw = rec.fixedResourceRate;
                        shownPct = true;
                    } else {
                        ParsedRate pr = resourceDevice.rateByTerminal.get(rec.terminalIdNorm);
                        raw = (pr == null ? null : pr.value);
                        shownPct = (pr != null && pr.shownAsPercent);

                        if (raw == null) {
                            raw = 0.0;        // 默认资源预警率 0
                            shownPct = true;
                        }
                    }

                    rr.rate = normalizeRateValue(raw, shownPct);
                    offsiteRows.add(rr);
                }
            }

            List<RowRec> all = new ArrayList<>(baseRows.size() + offsiteRows.size());
            all.addAll(baseRows);
            all.addAll(offsiteRows);

            // 资源预警率升序
            all.sort(Comparator.comparingDouble(a -> (a.rate == null ? 1e18 : a.rate)));

            // 最终排序后写序号
            for (int i = 0; i < all.size(); i++) all.get(i).seq = i + 1;

            writeRowsBack(wb, sheet, dataStart, hm, all, true);

            try (OutputStream os = Files.newOutputStream(out)) {
                wb.write(os);
            }
        }
    }

    // =====================================================================================
    // 读取模板网点行（带“是否显示为百分号”的标记）
    // =====================================================================================

    private List<RowRec> readOrgRows(Sheet sheet, int dataStart, HeaderMap hm, OffsiteData offsite) {
        DataFormatter df = new DataFormatter();
        List<RowRec> out = new ArrayList<>();

        int last = sheet.getLastRowNum();
        for (int r = dataStart; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            String branch = getCellTextWithMergedFallback(sheet, row, hm.branchCol, df).trim();
            if (branch.isBlank()) continue;

            if (normalizeBranch(branch).contains("合计")) continue;
            if (offsite.revokedBranches.contains(normalizeBranch(branch))) continue;

            ParsedRate pr = parseRateCell(row.getCell(hm.rateCol), df);

            RowRec rr = new RowRec();
            rr.branchName = branch;
            rr.branchNorm = normalizeBranch(branch);
            rr.rate = pr.value;
            rr.rateShownAsPercent = pr.shownAsPercent;
            rr.snapshot = RowSnapshot.capture(row, hm.maxColToCopy);
            out.add(rr);
        }
        return out;
    }

    // =====================================================================================
    // 写回模板
    // =====================================================================================

    private void writeRowsBack(Workbook wb,
                               Sheet sheet,
                               int dataStart,
                               HeaderMap hm,
                               List<RowRec> rows,
                               boolean keepDeviceTotalCol) {

        RowSnapshot styleBase = null;
        for (RowRec rr : rows) {
            if (rr.snapshot != null) { styleBase = rr.snapshot; break; }
        }
        if (styleBase == null) {
            Row base = sheet.getRow(dataStart);
            styleBase = (base == null) ? null : RowSnapshot.capture(base, hm.maxColToCopy);
        }

        int needLast = dataStart + rows.size() - 1;
        for (int r = dataStart; r <= needLast; r++) {
            if (sheet.getRow(r) == null) sheet.createRow(r);
        }

        CellStyle numberStyle = buildNumberStyle(wb, "0.00");

        for (int i = 0; i < rows.size(); i++) {
            RowRec rr = rows.get(i);
            int targetRowIdx = dataStart + i;
            Row target = sheet.getRow(targetRowIdx);
            if (target == null) target = sheet.createRow(targetRowIdx);

            RowSnapshot baseSnap = rr.snapshot != null ? rr.snapshot : styleBase;
            if (baseSnap != null) baseSnap.applyToRow(target);

            setCellNumber(target, hm.seqCol, rr.seq, null);
            setCellText(target, hm.branchCol, rr.branchName);

            if (rr.rate != null) setCellNumber(target, hm.rateCol, rr.rate, numberStyle);
            else getOrCreateCell(target, hm.rateCol).setBlank();

            if (keepDeviceTotalCol && hm.deviceTotalCol >= 0 && rr.deviceTotal != null) {
                setCellNumber(target, hm.deviceTotalCol, rr.deviceTotal, null);
            }

            if (rr.blankMiddle) blankMiddleColumns(target, hm, keepDeviceTotalCol);
        }

        int oldLast = sheet.getLastRowNum();
        int startRemove = dataStart + rows.size();
        for (int r = oldLast; r >= startRemove; r--) {
            Row row = sheet.getRow(r);
            if (row != null) sheet.removeRow(row);
        }
    }

    private void blankMiddleColumns(Row row, HeaderMap hm, boolean keepDeviceTotalCol) {
        for (int c = 0; c <= hm.maxColToCopy; c++) {
            if (c == hm.seqCol || c == hm.branchCol || c == hm.rateCol) continue;
            if (keepDeviceTotalCol && c == hm.deviceTotalCol) continue;
            Cell cell = row.getCell(c);
            if (cell != null) cell.setBlank();
        }
    }

    // =====================================================================================
    // 识别机构模板表头
    // =====================================================================================

    private HeaderMap detectOrgHeader(Sheet sheet, String[] branchKeys, String[] rateKeys) {
        int headerRow = 2;

        int branchCol = findColContainsAny(sheet, headerRow, branchKeys);
        int rateCol   = findColContainsAny(sheet, headerRow, rateKeys);

        if (branchCol < 0 || rateCol < 0) {
            int bc = -1, rc = -1, hr = -1;
            for (int r = 0; r <= 8; r++) {
                int tbc = findColContainsAny(sheet, r, branchKeys);
                int trc = findColContainsAny(sheet, r, rateKeys);
                if (tbc >= 0 && trc >= 0) { bc = tbc; rc = trc; hr = r; break; }
            }
            if (bc < 0 || rc < 0) throw new IllegalStateException("无法识别机构模板表头列");
            headerRow = hr;
            branchCol = bc;
            rateCol = rc;
        }

        int seqCol = findColContainsAny(sheet, headerRow, new String[]{"序号"});
        Row hr = sheet.getRow(headerRow);
        int maxCol = (hr == null) ? Math.max(branchCol, rateCol) : Math.max(Math.max(branchCol, rateCol), hr.getLastCellNum() - 1);

        HeaderMap hm = new HeaderMap();
        hm.headerRow = headerRow;
        hm.rateHeaderRow = headerRow;
        hm.seqCol = seqCol;
        hm.branchCol = branchCol;
        hm.rateCol = rateCol;
        hm.maxColToCopy = Math.max(maxCol, 0);
        hm.deviceTotalCol = -1;
        return hm;
    }

    // 使用 shiftColumns 插入序号列，避免 merged overlap
    private void ensureSeqColumnExistsSafe(Sheet sheet, HeaderMap hm) {
        if (hm.seqCol >= 0) return;

        int insertAt = 0;

        try {
            int maxCol = hm.maxColToCopy;
            Row header = sheet.getRow(hm.headerRow);
            if (header != null && header.getLastCellNum() > maxCol) {
                maxCol = header.getLastCellNum() - 1;
            }
            maxCol = Math.max(maxCol, 0);

            sheet.shiftColumns(insertAt, maxCol, 1);

            int w0 = sheet.getColumnWidth(1);
            sheet.setColumnWidth(0, Math.max(w0, 256 * 6));

            hm.seqCol = 0;
            hm.branchCol += 1;
            hm.rateCol += 1;
            if (hm.deviceTotalCol >= 0) hm.deviceTotalCol += 1;
            hm.maxColToCopy += 1;

            Row hr = sheet.getRow(hm.headerRow);
            if (hr == null) hr = sheet.createRow(hm.headerRow);
            Cell c = hr.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            c.setCellValue("序号");

        } catch (Exception ex) {
            int newCol = hm.maxColToCopy + 1;
            hm.seqCol = newCol;
            hm.maxColToCopy = newCol;

            Row hr = sheet.getRow(hm.headerRow);
            if (hr == null) hr = sheet.createRow(hm.headerRow);
            hr.getCell(newCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue("序号");

            System.out.println("[WARN] shiftColumns插列失败，已改为最右追加序号列。原因：" + ex.getMessage());
        }
    }

    private void stripPercentInHeader(Sheet sheet, int headerRow, int rateCol) {
        DataFormatter df = new DataFormatter();
        Row row = sheet.getRow(headerRow);
        if (row == null) return;
        Cell cell = row.getCell(rateCol, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return;

        String t = df.formatCellValue(cell);
        if (t == null) return;

        String nt = t.replace("(%)", "")
                .replace("（%）", "")
                .replace("%", "")
                .trim();
        if (!nt.equals(t)) cell.setCellValue(nt);
    }

    // =====================================================================================
    // 设备表读取
    // =====================================================================================



    private static class DeviceTable {
        final Map<String, List<String>> terminalsByBranch = new HashMap<>();
        final Map<String, ParsedRate> rateByTerminal = new HashMap<>();
        final Map<String, Integer> deviceTotalByBranch = new HashMap<>();
    }

    private DeviceTable loadBootDeviceTable(Path file) throws IOException {
        return loadDeviceTable(
                file,
                new String[]{"所属机构", "所属网点", "机构", "网点"},
                new String[]{"终端编号", "终端", "设备编号", "设备号"},
                new String[]{"开机率", "开机率(%)", "开机率%"}
        );
    }

    private DeviceTable loadResourceDeviceTable(Path file) throws IOException {
        return loadDeviceTable(
                file,
                new String[]{"所属网点", "所属机构", "机构", "网点"},
                new String[]{"设备编号", "终端编号", "终端", "设备号"},
                new String[]{"资源预警率", "资源预警率(%)", "资源预警率%"}
        );
    }

    private DeviceTable loadDeviceTable(Path file,
                                        String[] branchKeys,
                                        String[] terminalKeys,
                                        String[] rateKeys) throws IOException {

        DeviceTable dt = new DeviceTable();
        DataFormatter df = new DataFormatter();

        try (InputStream in = Files.newInputStream(file);
             Workbook wb = new XSSFWorkbook(in)) {

            Sheet sheet = wb.getSheetAt(0);

            int headerRow = -1, branchCol = -1, termCol = -1, rateCol = -1;
            int bestScore = -1;

            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 50); r++) {
                int bc = findColContainsAny(sheet, r, branchKeys);
                int tc = findColContainsAny(sheet, r, terminalKeys);
                int rc = findColContainsAny(sheet, r, rateKeys);
                int score = (bc >= 0 ? 3 : 0) + (tc >= 0 ? 3 : 0) + (rc >= 0 ? 3 : 0);

                if (score > bestScore) {
                    bestScore = score;
                    headerRow = r;
                    branchCol = bc;
                    termCol = tc;
                    rateCol = rc;
                }
            }

            if (branchCol < 0 || termCol < 0 || rateCol < 0) {
                throw new IllegalStateException("无法识别设备表列: " + file.getFileName()
                        + " | branchCol=" + branchCol + " termCol=" + termCol + " rateCol=" + rateCol);
            }

            int dataStart = headerRow + 1;
            for (int r = headerRow + 1; r <= Math.min(sheet.getLastRowNum(), headerRow + 80); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String b = getCellTextWithMergedFallback(sheet, row, branchCol, df).trim();
                String t = getCellTextWithMergedFallback(sheet, row, termCol, df).trim();
                if (!b.isBlank() && !t.isBlank()) { dataStart = r; break; }
            }

            for (int r = dataStart; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String branch = getCellTextWithMergedFallback(sheet, row, branchCol, df).trim();
                String term   = getCellTextWithMergedFallback(sheet, row, termCol, df).trim();
                if (branch.isBlank() || term.isBlank()) continue;

                String bNorm = normalizeBranch(branch);
                String tNorm = normalizeTerminal(term);

                ParsedRate pr = parseRateCell(row.getCell(rateCol), df);
                dt.rateByTerminal.put(tNorm, pr.value != null ? pr : new ParsedRate(null, false));
                dt.terminalsByBranch.computeIfAbsent(bNorm, k -> new ArrayList<>()).add(tNorm);
            }

            for (Map.Entry<String, List<String>> e : dt.terminalsByBranch.entrySet()) {
                dt.deviceTotalByBranch.put(e.getKey(), e.getValue().size());
            }
        }

        System.out.println("[INFO] loadDeviceTable=" + file.getFileName()
                + " branches=" + dt.terminalsByBranch.size()
                + " terminals=" + dt.rateByTerminal.size());
        return dt;
    }

    // =====================================================================================
    // 离行目录读取
    // =====================================================================================

    private static class OffsiteRecord {
        final String terminalIdNorm;
        final String branchNorm;
        final boolean includeInBranchScope;   // C
        final boolean includeInBranchAverage; // D
        final String displayName;             // 直接复制
        final Double fixedBootRate;           // 开机率固定值（%）
        final Double fixedResourceRate;       // 资源预警率固定值（%）

        OffsiteRecord(String terminalId,
                      String branchName,
                      boolean includeInBranchScope,
                      boolean includeInBranchAverage,
                      String displayName,
                      Double fixedBootRate,
                      Double fixedResourceRate) {

            this.terminalIdNorm = normalizeTerminal(terminalId);
            this.branchNorm = normalizeBranch(branchName);
            this.includeInBranchScope = includeInBranchScope;
            this.includeInBranchAverage = includeInBranchAverage;
            this.displayName = (displayName == null || displayName.isBlank())
                    ? (branchName + "（离行）")
                    : displayName.trim();
            this.fixedBootRate = fixedBootRate;
            this.fixedResourceRate = fixedResourceRate;
        }
    }

    private static class OffsiteData {
        final List<OffsiteRecord> records = new ArrayList<>();
        final Set<String> revokedBranches = new HashSet<>();

        Map<String, Integer> excludedOffsiteCountByBranch() {
            Map<String, Integer> m = new HashMap<>();
            for (OffsiteRecord r : records) {
                if (!r.includeInBranchScope && !r.includeInBranchAverage) {
                    m.merge(r.branchNorm, 1, Integer::sum);
                }
            }
            return m;
        }

        Set<String> excludedTerminalSetForBranch(String branchNorm) {
            Set<String> s = new HashSet<>();
            for (OffsiteRecord r : records) {
                if (r.branchNorm.equals(branchNorm) && !r.includeInBranchScope && !r.includeInBranchAverage) {
                    s.add(r.terminalIdNorm);
                }
            }
            return s;
        }
    }

    private OffsiteData loadOffsiteData(Path catalog) throws IOException {
        OffsiteData data = new OffsiteData();
        DataFormatter df = new DataFormatter();

        try (InputStream in = Files.newInputStream(catalog);
             Workbook wb = new XSSFWorkbook(in)) {

            Sheet s1 = wb.getSheetAt(0);
            int headerRow = 0;

            int branchCol = findColContainsAny(s1, headerRow, new String[]{"所属网点", "所属机构", "所属"});
            int terminalCol = findColContainsAny(s1, headerRow, new String[]{"终端编号", "设备编号", "终端", "设备号"});
            int inScopeCol = findColContainsAny(s1, headerRow, new String[]{"是否纳入本网点接管", "接管"});
            int inAvgCol = findColContainsAny(s1, headerRow, new String[]{"是否纳入网点均值", "均值"});
            int displayCol = findColContainsAny(s1, headerRow, new String[]{"直接复制", "复制"});
            int fixedBootCol = findColContainsAny(s1, headerRow, new String[]{"开机率固定值", "开机固定"});
            int fixedResCol = findColContainsAny(s1, headerRow, new String[]{"资源预警率固定值", "资源预警固定"});

            if (branchCol < 0 || terminalCol < 0 || inScopeCol < 0 || inAvgCol < 0) {
                throw new IllegalStateException("无法识别离行设备目录Sheet1表头（至少要识别：所属网点/终端编号/是否纳入本网点接管/是否纳入网点均值）");
            }

            for (int r = 1; r <= s1.getLastRowNum(); r++) {
                Row row = s1.getRow(r);
                if (row == null) continue;

                String branch = getCellTextWithMergedFallback(s1, row, branchCol, df).trim();
                String term = getCellTextWithMergedFallback(s1, row, terminalCol, df).trim();
                if (branch.isBlank() || term.isBlank()) continue;

                boolean inScope = parseYN(getCellTextWithMergedFallback(s1, row, inScopeCol, df));
                boolean inAvg = parseYN(getCellTextWithMergedFallback(s1, row, inAvgCol, df));
                String display = displayCol >= 0 ? getCellTextWithMergedFallback(s1, row, displayCol, df).trim() : "";

                Double fixedBoot = fixedBootCol >= 0 ? parseMaybeRate(getCellTextWithMergedFallback(s1, row, fixedBootCol, df)) : null;
                Double fixedRes = fixedResCol >= 0 ? parseMaybeRate(getCellTextWithMergedFallback(s1, row, fixedResCol, df)) : null;

                data.records.add(new OffsiteRecord(term, branch, inScope, inAvg, display, fixedBoot, fixedRes));
            }

            if (wb.getNumberOfSheets() > 1) {
                Sheet s2 = wb.getSheetAt(1);
                for (int r = 1; r <= s2.getLastRowNum(); r++) {
                    Row row = s2.getRow(r);
                    if (row == null) continue;
                    for (int c = 0; c < Math.max(1, row.getLastCellNum()); c++) {
                        String v = getCellTextWithMergedFallback(s2, row, c, df).trim();
                        if (!v.isBlank()) data.revokedBranches.add(normalizeBranch(v));
                    }
                }
            }
        }

        System.out.println("[INFO] offsiteRecords=" + data.records.size()
                + " revokedBranches=" + data.revokedBranches.size());
        return data;
    }

    // =====================================================================================
    // Row snapshot（保留样式）
    // =====================================================================================

    private static class RowSnapshot {
        final List<CellSnapshot> cells;

        RowSnapshot(List<CellSnapshot> cells) {
            this.cells = cells;
        }

        static RowSnapshot capture(Row row, int lastCol) {
            List<CellSnapshot> list = new ArrayList<>();
            for (int c = 0; c <= lastCol; c++) {
                Cell cell = row.getCell(c);
                list.add(CellSnapshot.capture(cell, c));
            }
            return new RowSnapshot(list);
        }

        void applyToRow(Row target) {
            for (CellSnapshot cs : cells) cs.applyTo(target);
        }
    }

    private static class CellSnapshot {
        final int col;
        final CellType type;
        final Object value;
        final CellStyle style;

        CellSnapshot(int col, CellType type, Object value, CellStyle style) {
            this.col = col;
            this.type = type;
            this.value = value;
            this.style = style;
        }

        static CellSnapshot capture(Cell cell, int col) {
            if (cell == null) return new CellSnapshot(col, CellType.BLANK, null, null);
            CellType t = cell.getCellType();
            Object v = null;
            if (t == CellType.STRING) v = cell.getStringCellValue();
            else if (t == CellType.NUMERIC) v = cell.getNumericCellValue();
            else if (t == CellType.BOOLEAN) v = cell.getBooleanCellValue();
            else if (t == CellType.FORMULA) v = cell.getCellFormula();
            return new CellSnapshot(col, t, v, cell.getCellStyle());
        }

        void applyTo(Row row) {
            Cell cell = row.getCell(col);
            if (cell == null) cell = row.createCell(col);
            if (style != null) cell.setCellStyle(style);

            switch (type) {
                case STRING -> cell.setCellValue(value == null ? "" : String.valueOf(value));
                case NUMERIC -> {
                    if (value instanceof Double d) cell.setCellValue(d);
                    else if (value instanceof Number n) cell.setCellValue(n.doubleValue());
                    else cell.setBlank();
                }
                case BOOLEAN -> {
                    if (value instanceof Boolean b) cell.setCellValue(b);
                    else cell.setBlank();
                }
                case FORMULA -> {
                    if (value instanceof String f) cell.setCellFormula(f);
                    else cell.setBlank();
                }
                default -> cell.setBlank();
            }
        }
    }

    private static class RowRec {
        int seq;
        String branchName;
        String branchNorm;
        Double rate;
        boolean rateShownAsPercent;
        boolean blankMiddle;
        Integer deviceTotal;
        RowSnapshot snapshot;

        static RowRec newOffsiteStandalone(String name) {
            RowRec rr = new RowRec();
            rr.branchName = name;
            rr.branchNorm = normalizeBranch(name);
            rr.snapshot = null;
            return rr;
        }
    }

    private static class HeaderMap {
        int headerRow;
        int rateHeaderRow;
        int seqCol;
        int branchCol;
        int rateCol;
        int deviceTotalCol;
        int maxColToCopy;
    }

    // =====================================================================================
    // 找列（支持 merged 表头回填）
    // =====================================================================================

    private int findColContainsAny(Sheet sheet, int rowIdx, String[] keys) {
        DataFormatter df = new DataFormatter();
        Row row = sheet.getRow(rowIdx);
        if (row == null) return -1;

        int last = Math.max(row.getLastCellNum(), 0);
        int scanMax = Math.max(last + 30, 80);

        for (int c = 0; c < scanMax; c++) {
            String v = getCellTextWithMergedFallback(sheet, row, c, df);
            if (v == null || v.isBlank()) continue;
            String nv = normalizeBasic(v);
            for (String k : keys) {
                if (k == null) continue;
                String nk = normalizeBasic(k);
                if (!nk.isBlank() && nv.contains(nk)) return c;
            }
        }
        return -1;
    }

    private static String getCellTextWithMergedFallback(Sheet sheet, Row row, int col, DataFormatter df) {
        Cell cell = row.getCell(col);
        String v = (cell == null) ? "" : df.formatCellValue(cell);
        if (v != null && !v.trim().isBlank()) return v;

        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress m = sheet.getMergedRegion(i);
            if (m.isInRange(row.getRowNum(), col)) {
                Row r0 = sheet.getRow(m.getFirstRow());
                if (r0 == null) break;
                Cell c0 = r0.getCell(m.getFirstColumn());
                if (c0 == null) break;
                return df.formatCellValue(c0);
            }
        }
        return v == null ? "" : v;
    }

    // =====================================================================================
    // 单元格工具
    // =====================================================================================

    private static Cell getOrCreateCell(Row row, int col) {
        Cell c = row.getCell(col);
        if (c == null) c = row.createCell(col);
        return c;
    }

    private static void setCellText(Row row, int col, String text) {
        Cell c = getOrCreateCell(row, col);
        c.setCellValue(text == null ? "" : text);
    }

    private static void setCellNumber(Row row, int col, double val, CellStyle style) {
        Cell c = getOrCreateCell(row, col);
        c.setCellValue(val);
        if (style != null) c.setCellStyle(style);
    }

    private static void setCellNumber(Row row, int col, int val, CellStyle style) {
        Cell c = getOrCreateCell(row, col);
        c.setCellValue(val);
        if (style != null) c.setCellStyle(style);
    }

    private static CellStyle buildNumberStyle(Workbook wb, String fmt) {
        CellStyle cs = wb.createCellStyle();
        DataFormat df = wb.createDataFormat();
        cs.setDataFormat(df.getFormat(fmt));
        return cs;
    }

    // =====================================================================================
    // Rate 解析（保留“是否显示为%”）
    // =====================================================================================

    private static class ParsedRate {
        final Double value;
        final boolean shownAsPercent;
        ParsedRate(Double value, boolean shownAsPercent) {
            this.value = value;
            this.shownAsPercent = shownAsPercent;
        }
    }

    private static ParsedRate parseRateCell(Cell cell, DataFormatter df) {
        if (cell == null) return new ParsedRate(null, false);

        String shown = df.formatCellValue(cell);
        if (shown == null) return new ParsedRate(null, false);

        shown = shown.trim();
        if (shown.isBlank()) return new ParsedRate(null, false);

        String raw = shown.replace("％", "%").replace(",", "").trim();
        boolean hasPercent = raw.contains("%");
        raw = raw.replace("%", "").trim();

        try {
            double v = Double.parseDouble(raw);
            return new ParsedRate(v, hasPercent);
        } catch (NumberFormatException e) {
            return new ParsedRate(null, hasPercent);
        }
    }

    // ✅ 修复：若原始显示带%（例如 0.93%），禁止再*100
    private static double normalizeRateValue(Double v, boolean shownAsPercent) {
        if (v == null) return 0.0;
        if (shownAsPercent) return v;

        double x = v;
        if (x >= 0 && x <= 1.0 && Math.abs(x) > 1e-12 && Math.abs(x - 1.0) > 1e-12) {
            x = x * 100.0;
        }
        return x;
    }

    private static Double parseMaybeRate(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isBlank()) return null;

        t = t.replace("％", "%").replace(",", "").trim();
        boolean hasPercent = t.contains("%");
        t = t.replace("%", "").trim();

        try {
            double v = Double.parseDouble(t);
            return normalizeRateValue(v, hasPercent);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean parseYN(String s) {
        if (s == null) return false;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.equals("Y") || t.equals("YES") || t.equals("是") || t.equals("1") || t.equals("TRUE");
    }

    // =====================================================================================
    // 字符串规范化
    // =====================================================================================

    private static String normalizeBasic(String s) {
        if (s == null) return "";
        return s.trim()
                .replace("\u00A0", " ")
                .replace("（", "(").replace("）", ")")
                .replace("：", ":")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeBranch(String s) {
        return normalizeBasic(s).replace(" ", "");
    }

    private static String normalizeTerminal(String s) {
        if (s == null) return "";
        return s.trim()
                .replace(" ", "")
                .replace("\u00A0", "")
                .toUpperCase(Locale.ROOT);
    }
}