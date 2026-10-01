package com.endpointposture.warranty;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for warranty data management.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/warranty} — list all warranty records (any authenticated user)</li>
 *   <li>{@code POST /api/v1/warranty/upload} — upload a CSV file (ADMIN only)</li>
 * </ul>
 *
 * <p>Reads are open to any authenticated user; writing is ADMIN-only both here
 * ({@code @PreAuthorize}) and in {@code SecurityConfig} (defence in depth).</p>
 */
@RestController
@RequestMapping("/api/v1/warranty")
public class WarrantyController {

    private final WarrantyService service;

    public WarrantyController(WarrantyService service) {
        this.service = service;
    }

    /**
     * Returns the full warranty record list, newest-upload first.
     * Each row includes all fields; the UI deduplicates by serial for the summary view.
     */
    @GetMapping
    public List<WarrantyView> listAll() {
        return service.listAll().stream().map(WarrantyView::of).toList();
    }

    /**
     * Accepts a multipart CSV upload.
     *
     * <p>ADMIN only (enforced in {@code SecurityConfig} and re-checked here for defence
     * in depth). The {@code Authentication} is injected by Spring Security from the
     * JWT, so the uploader's username is always the real JWT subject, never a parameter.</p>
     *
     * @return 200 with {@link WarrantyUploadResult} on success,
     *         400 with an error message on parse failure
     */
    @PostMapping("/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    Authentication auth) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Uploaded file is empty"));
        }

        try {
            WarrantyUploadResult result = service.ingest(file, auth.getName());
            return ResponseEntity.ok(result);
        } catch (WarrantyParseException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    // ── DTO ──────────────────────────────────────────────────────────────────

    /**
     * Read-only view of a single warranty record row.
     *
     * @param id             UUID of this row
     * @param serialNumber   device serial number
     * @param vendor         hardware vendor
     * @param expiresOn      warranty expiry date (ISO-8601)
     * @param productName    optional product description
     * @param daysRemaining  positive = still covered; 0 = today; negative = expired
     * @param status         COVERED, EXPIRING_SOON (≤30 d), EXPIRED, or UNKNOWN
     * @param source         data source (CSV_UPLOAD or future OEM API)
     * @param uploadedBy     username of the admin who uploaded this row
     * @param uploadedAt     when this row was persisted
     */
    public record WarrantyView(
            String id,
            String serialNumber,
            String vendor,
            LocalDate expiresOn,
            String productName,
            long daysRemaining,
            String status,
            String source,
            String uploadedBy,
            String uploadedAt) {

        static WarrantyView of(WarrantyRecord r) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), r.getExpiresOn());
            String status = days < 0 ? "EXPIRED" : days <= 30 ? "EXPIRING_SOON" : "COVERED";
            return new WarrantyView(
                    r.getId().toString(),
                    r.getSerialNumber(),
                    r.getVendor(),
                    r.getExpiresOn(),
                    r.getProductName(),
                    days,
                    status,
                    r.getSource(),
                    r.getUploadedBy(),
                    r.getUploadedAt().toString());
        }
    }
}
