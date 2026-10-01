package com.endpointposture.warranty;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row from a warranty CSV upload.
 *
 * <p>Multiple rows with the same {@code serialNumber} are allowed: re-uploading
 * a corrected CSV preserves history. The service picks the row with the latest
 * {@code uploadedAt} when joining to hardware health.</p>
 */
@Entity
@Table(name = "warranty_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarrantyRecord {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "serial_number", nullable = false)
    private String serialNumber;

    @Column(name = "vendor", nullable = false)
    private String vendor;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "source", nullable = false)
    @Builder.Default
    private String source = "CSV_UPLOAD";

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant uploadedAt = Instant.now();
}
