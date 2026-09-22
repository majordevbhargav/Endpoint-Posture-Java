package com.endpointposture.hardware.scoring;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Scores storage health as the fraction of physical disks reporting
 * {@code Healthy} (or {@code 0}, PowerShell's numeric health-status
 * code for healthy) out of all disks in
 * {@code storage.physical_disks}. One failing disk in a multi-disk
 * machine drags the score down proportionally rather than an all-or-
 * nothing pass/fail.
 */
@Component
public class StorageScorer implements ComponentScorer<List<Map<String, Object>>> {

    @Override
    public Integer score(List<Map<String, Object>> disks) {
        if (disks == null || disks.isEmpty()) return null;

        long healthy = disks.stream()
                .filter(d -> {
                    Object status = d.get("HealthStatus");
                    String s = status == null ? "" : status.toString().trim().toLowerCase();
                    return s.equals("healthy") || s.equals("0");
                })
                .count();

        return (int) Math.round((healthy / (double) disks.size()) * 100);
    }
}