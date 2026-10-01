package com.endpointposture.warranty;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a warranty CSV and bulk-inserts {@link WarrantyRecord} rows.
 *
 * <h3>Expected CSV format</h3>
 * <pre>
 * serial_number,vendor,expires_on,product_name
 * SN12345,Dell,2027-06-30,Latitude 5540
 * ...
 * </pre>
 *
 * <ul>
 *   <li>The header row is mandatory; column names are matched case-insensitively
 *       so minor OEM variation ("{@code SerialNumber}" vs "{@code serial_number}") is
 *       tolerated.</li>
 *   <li>{@code product_name} is optional; all other columns are required.</li>
 *   <li>{@code expires_on} accepts ISO-8601 ({@code yyyy-MM-dd}) and the common
 *       US format ({@code MM/dd/yyyy}).</li>
 *   <li>Rows with a missing or blank serial number are skipped with a warning.</li>
 *   <li>Existing rows for the same serial number are <em>not</em> deleted: the
 *       new upload is appended and the latest row wins at query time.</li>
 * </ul>
 */
@Service
public class WarrantyService {

    private static final DateTimeFormatter ISO   = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter US    = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final WarrantyRecordRepository repo;

    public WarrantyService(WarrantyRecordRepository repo) {
        this.repo = repo;
    }

    /** @return list of all warranty records, newest first */
    public List<WarrantyRecord> listAll() {
        return repo.findAllByOrderByUploadedAtDesc();
    }

    /** @return the latest warranty record for a serial number, or {@code null} */
    public WarrantyRecord findLatest(String serialNumber) {
        return repo.findLatestBySerialNumber(serialNumber).orElse(null);
    }

    /**
     * Parses {@code file} and bulk-saves all valid rows.
     *
     * @param file       the uploaded CSV file
     * @param uploadedBy JWT subject of the ADMIN user performing the upload
     * @return parse result describing rows saved and any row-level errors
     * @throws WarrantyParseException if the file cannot be read or has no header
     */
    @Transactional
    public WarrantyUploadResult ingest(MultipartFile file, String uploadedBy) {
        List<WarrantyRecord> toSave = new ArrayList<>();
        List<String> rowErrors      = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new WarrantyParseException("CSV file is empty or has no header row");
            }

            String[] headers = headerLine.split(",", -1);
            int idxSerial  = findCol(headers, "serial_number", "serialnumber", "serial");
            int idxVendor  = findCol(headers, "vendor");
            int idxExpires = findCol(headers, "expires_on", "expireson", "expiry", "warranty_end", "warrantyend");
            int idxProduct = findOptionalCol(headers, "product_name", "productname", "product", "model");

            if (idxSerial < 0 || idxVendor < 0 || idxExpires < 0) {
                throw new WarrantyParseException(
                        "CSV header must contain columns: serial_number, vendor, expires_on. " +
                        "Found: " + headerLine);
            }

            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;

                String[] cols = line.split(",", -1);
                String serial = safeGet(cols, idxSerial).trim();
                if (serial.isEmpty()) {
                    rowErrors.add("Row " + lineNum + ": serial_number is blank — skipped");
                    continue;
                }

                String vendor = safeGet(cols, idxVendor).trim();
                if (vendor.isEmpty()) {
                    rowErrors.add("Row " + lineNum + " (serial=" + serial + "): vendor is blank — skipped");
                    continue;
                }

                String expiresStr = safeGet(cols, idxExpires).trim();
                LocalDate expires;
                try {
                    expires = parseDate(expiresStr);
                } catch (DateTimeParseException e) {
                    rowErrors.add("Row " + lineNum + " (serial=" + serial + "): unrecognised date format '" +
                            expiresStr + "' — skipped (use yyyy-MM-dd or MM/dd/yyyy)");
                    continue;
                }

                String productName = (idxProduct >= 0) ? safeGet(cols, idxProduct).trim() : null;

                toSave.add(WarrantyRecord.builder()
                        .serialNumber(serial)
                        .vendor(vendor)
                        .expiresOn(expires)
                        .productName(productName == null || productName.isEmpty() ? null : productName)
                        .uploadedBy(uploadedBy)
                        .build());
            }

        } catch (WarrantyParseException e) {
            throw e;
        } catch (Exception e) {
            throw new WarrantyParseException("Failed to read CSV: " + e.getMessage(), e);
        }

        repo.saveAll(toSave);
        return new WarrantyUploadResult(toSave.size(), rowErrors);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static LocalDate parseDate(String s) {
        // Try ISO first, then US-style.
        try { return LocalDate.parse(s, ISO); } catch (DateTimeParseException ignored) {}
        return LocalDate.parse(s, US);
    }

    /** Returns the 0-based index of the first header that matches any alias (case-insensitive). */
    private static int findCol(String[] headers, String... aliases) {
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase().replace(" ", "_").replace("-", "_");
            for (String alias : aliases) {
                if (h.equals(alias)) return i;
            }
        }
        return -1;
    }

    private static int findOptionalCol(String[] headers, String... aliases) {
        return findCol(headers, aliases); // same logic; caller decides what to do with -1
    }

    private static String safeGet(String[] cols, int idx) {
        return (idx >= 0 && idx < cols.length) ? cols[idx] : "";
    }
}
