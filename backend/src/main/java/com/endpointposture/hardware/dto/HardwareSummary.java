package com.endpointposture.hardware.dto;

/** Fleet hardware numbers from the endpoint table, so the KPI cards never need the whole list. */
public record HardwareSummary(
        long total,
        long withReport,
        long avgScore,
        long criticalOrDegraded,
        long batteryWarnings,
        long lastAttemptFailed,
        long neverCollected,
        long noReport,
        long recommendations
    ) {
}