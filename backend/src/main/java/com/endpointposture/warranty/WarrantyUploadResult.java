package com.endpointposture.warranty;

import java.util.List;

/**
 * Summary returned after a CSV upload attempt.
 *
 * @param savedCount number of rows successfully parsed and persisted
 * @param rowErrors  per-row error messages for rows that were skipped
 */
public record WarrantyUploadResult(int savedCount, List<String> rowErrors) {}
