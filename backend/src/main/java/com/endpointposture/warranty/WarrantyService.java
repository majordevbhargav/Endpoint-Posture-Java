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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses warranty CSVs, stores {@link WarrantyRecord} rows, and answers
 * "what is the warranty state of this serial" for hardware health.
 *
 * <p>CSV: header row required (case-insensitive aliases), columns
 * serial_number, vendor, expires_on (yyyy-MM-dd or MM/dd/yyyy), optional
 * product_name. A UTF-8 BOM and quoted fields containing commas are handled.
 * Existing rows are never deleted; the newest upload wins at query time.</p>
 */
@Service
public class WarrantyService {

    /** Days at or below which a warranty is "expiring soon". */
    static final int EXPIRING_SOON_DAYS = 30;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter US = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /** Warranty state derived from the newest record for a serial. */
    public record Info(String status, int daysRemaining) {}

    private final WarrantyRecordRepository repo;

    public WarrantyService(WarrantyRecordRepository repo) {
        this.repo = repo;
    }

    /** The ONE serial normalization, used at every read and write. */
    static String normalizeSerial(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** COVERED, EXPIRING_SOON or EXPIRED from days remaining. */
    public static String statusFor(long daysRemaining) {
        if (daysRemaining < 0) return "EXPIRED";
        return daysRemaining <= EXPIRING_SOON_DAYS ? "EXPIRING_SOON" : "COVERED";
    }

    @Transactional(readOnly = true)
    public List<WarrantyRecord> listAll() {
        return repo.findAllByOrderByUploadedAtDesc();
    }

    @Transactional(readOnly = true)
    public WarrantyRecord findLatest(String serialNumber) {
        return repo.findLatestBySerialNumber(normalizeSerial(serialNumber)).orElse(null);
    }

    /**
     * @return state for this serial, or {@code null} when the serial is blank
     *         or has no record (callers keep whatever they already had)
     */
    @Transactional(readOnly = true)
    public Info describe(String serialNumber) {
        if (serialNumber == null || serialNumber.isBlank()) return null;
        return repo.findLatestBySerialNumber(normalizeSerial(serialNumber)).map(r -> {
            long days = ChronoUnit.DAYS.between(LocalDate.now(), r.getExpiresOn());
            return new Info(statusFor(days), (int) days);
        }).orElse(null);
    }

    @Transactional
    public WarrantyUploadResult ingest(MultipartFile file, String uploadedBy) {
        List<WarrantyRecord> toSave = new ArrayList<>();
        List<String> rowErrors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine != null && headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1); // Excel "CSV UTF-8" BOM
            }
            if (headerLine == null || headerLine.isBlank()) {
                throw new WarrantyParseException("CSV file is empty or has no header row");
            }

            List<String> headers = splitCsv(headerLine);
            int idxSerial = findCol(headers, "serial_number", "serialnumber", "serial");
            int idxVendor = findCol(headers, "vendor");
            int idxExpires = findCol(headers, "expires_on", "expireson", "expiry", "warranty_end", "warrantyend");
            int idxProduct = findCol(headers, "product_name", "productname", "product", "model");

            if (idxSerial < 0 || idxVendor < 0 || idxExpires < 0) {
                throw new WarrantyParseException(
                        "CSV header must contain columns: serial_number, vendor, expires_on. Found: " + headerLine);
            }

            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;

                List<String> cols = splitCsv(line);
                String serial = normalizeSerial(safeGet(cols, idxSerial));
                if (serial.isEmpty()) {
                    rowErrors.add("Row " + lineNum + ": serial_number is blank, skipped");
                    continue;
                }
                String vendor = safeGet(cols, idxVendor).trim();
                if (vendor.isEmpty()) {
                    rowErrors.add("Row " + lineNum + " (serial=" + serial + "): vendor is blank, skipped");
                    continue;
                }
                String expiresStr = safeGet(cols, idxExpires).trim();
                LocalDate expires;
                try {
                    expires = parseDate(expiresStr);
                } catch (DateTimeParseException e) {
                    rowErrors.add("Row " + lineNum + " (serial=" + serial + "): unrecognised date '"
                            + expiresStr + "', skipped (use yyyy-MM-dd or MM/dd/yyyy)");
                    continue;
                }
                String product = idxProduct >= 0 ? safeGet(cols, idxProduct).trim() : "";

                toSave.add(WarrantyRecord.builder()
                        .serialNumber(serial)
                        .vendor(vendor)
                        .expiresOn(expires)
                        .productName(product.isEmpty() ? null : product)
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

    // ── helpers ──

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s, ISO);
        } catch (DateTimeParseException ignored) {
            // fall through to US format
        }
        return LocalDate.parse(s, US);
    }

    /** Quote-aware single-line CSV split ("" inside quotes is a literal quote). */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static int findCol(List<String> headers, String... aliases) {
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).trim().toLowerCase(Locale.ROOT).replace(" ", "_").replace("-", "_");
            for (String alias : aliases) {
                if (h.equals(alias)) return i;
            }
        }
        return -1;
    }

    private static String safeGet(List<String> cols, int idx) {
        return idx >= 0 && idx < cols.size() ? cols.get(idx) : "";
    }
}