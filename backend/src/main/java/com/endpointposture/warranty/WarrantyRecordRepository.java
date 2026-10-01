package com.endpointposture.warranty;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Data access for {@link WarrantyRecord}. */
public interface WarrantyRecordRepository extends JpaRepository<WarrantyRecord, UUID> {

    /**
     * Newest row for a serial. Compares trimmed + uppercased so rows saved
     * before normalization existed still match. Pass an already-normalized serial.
     */
    @Query("SELECT w FROM WarrantyRecord w WHERE UPPER(TRIM(w.serialNumber)) = :serial ORDER BY w.uploadedAt DESC LIMIT 1")
    Optional<WarrantyRecord> findLatestBySerialNumber(@Param("serial") String serial);

    List<WarrantyRecord> findBySerialNumberOrderByUploadedAtDesc(String serialNumber);

    List<WarrantyRecord> findAllByOrderByUploadedAtDesc();

    @Query("SELECT COUNT(DISTINCT w.serialNumber) FROM WarrantyRecord w")
    long countDistinctSerials();
}