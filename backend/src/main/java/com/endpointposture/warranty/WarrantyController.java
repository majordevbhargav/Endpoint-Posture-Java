package com.endpointposture.warranty;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Warranty data. Reads: any authenticated user. Upload: ADMIN only, enforced
 * both in {@code SecurityConfig} and by {@code @PreAuthorize} here.
 */
@RestController
@RequestMapping("/api/v1/warranty")
public class WarrantyController {

    private final WarrantyService service;

    public WarrantyController(WarrantyService service) {
        this.service = service;
    }

    @GetMapping
    public List<WarrantyView> listAll() {
        return service.listAll().stream().map(WarrantyView::of).toList();
    }

    @PostMapping("/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file, Authentication auth) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Uploaded file is empty"));
        }
        try {
            return ResponseEntity.ok(service.ingest(file, auth.getName()));
        } catch (WarrantyParseException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    /** Read-only row view; status is derived from today's date. */
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
            long days = ChronoUnit.DAYS.between(LocalDate.now(), r.getExpiresOn());
            return new WarrantyView(
                    r.getId().toString(),
                    r.getSerialNumber(),
                    r.getVendor(),
                    r.getExpiresOn(),
                    r.getProductName(),
                    days,
                    WarrantyService.statusFor(days),
                    r.getSource(),
                    r.getUploadedBy(),
                    r.getUploadedAt().toString());
        }
    }
}