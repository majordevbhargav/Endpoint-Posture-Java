package com.endpointposture.warranty;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link WarrantyRecord}.
 */
public interface WarrantyRecordRepository extends JpaRepository<WarrantyRecord, UUID> {

    /**
     * Returns the most-recently-uploaded warranty row for the given serial number.
     * Used to derive {@code warrantyStatus} and {@code warrantyDaysRemaining}.
     */
    @Query("SELECT w FROM WarrantyRecord w WHERE w.serialNumber = :serial ORDER BY w.uploadedAt DESC LIMIT 1")
    Optional<WarrantyRecord> findLatestBySerialNumber(@Param("serial") String serial);

    /**
     * All rows for a serial, newest first — for the admin detail view.
     */
    List<WarrantyRecord> findBySerialNumberOrderByUploadedAtDesc(String serialNumber);

    /**
     * All rows, newest first — for the fleet summary page.
     */
    List<WarrantyRecord> findAllByOrderByUploadedAtDesc();

    /**
     * Count of distinct serial numbers with at least one warranty record.
     */
    @Query("SELECT COUNT(DISTINCT w.serialNumber) FROM WarrantyRecord w")
    long countDistinctSerials();
}
