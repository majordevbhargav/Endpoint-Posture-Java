package com.endpointposture.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.endpointposture.job.JobType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Logical result envelope submitted by an authenticated worker.
 * This contract is not exposed by an HTTP endpoint in PR6.4 Slice 1.
 */
public record WorkerResultRequest(
        @Min(1) int attempt,
        @Min(1) int schemaVersion,
        @NotNull JobType jobType,
        @NotNull CollectionOutcome collectionOutcome,
        JsonNode reportPayload,
        @NotNull @Size(max = 100) List<@NotNull @Size(max = 4096) String> errors,
        @NotNull @Size(max = 100) List<@NotNull @Size(max = 4096) String> warnings,
        @NotNull @Min(0) Integer processExitCode) {

    public static final int MAX_REPORT_PAYLOAD_BYTES = 1_048_576;
    public static final int MAX_DIAGNOSTIC_TEXT_BYTES = 65_536;

    public enum CollectionOutcome { SUCCESS, PARTIAL, FAILED }

    @AssertTrue(message = "reportPayload must be an object for SUCCESS/PARTIAL and explicit JSON null for FAILED")
    public boolean isReportPayloadValid() {
        if (collectionOutcome == CollectionOutcome.FAILED) {
            return reportPayload != null && reportPayload.isNull();
        }
        return reportPayload != null && reportPayload.isObject();
    }

    @AssertTrue(message = "reportPayload must not exceed 1 MiB")
    public boolean isReportPayloadWithinLimit() {
        return reportPayload == null || reportPayload.isNull()
                || reportPayload.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_REPORT_PAYLOAD_BYTES;
    }

    @AssertTrue(message = "errors and warnings must not exceed 64 KiB combined")
    public boolean areDiagnosticsWithinLimit() {
        if (errors == null || warnings == null) return true;
        long bytes = 0;
        for (String value : errors) if (value != null) bytes += value.getBytes(StandardCharsets.UTF_8).length;
        for (String value : warnings) if (value != null) bytes += value.getBytes(StandardCharsets.UTF_8).length;
        return bytes <= MAX_DIAGNOSTIC_TEXT_BYTES;
    }

    /**
     * SHA-256 over every envelope field. Object keys are recursively sorted,
     * while array order and scalar values are preserved.
     */
    /**
     * Keeps the process result and collection diagnostics semantically consistent.
     * Shared by Bean Validation and the service guard for non-HTTP callers.
     */
    @AssertTrue(message = "collectionOutcome, processExitCode, errors and warnings are inconsistent")
    public boolean isProcessOutcomeConsistent() {
        if (collectionOutcome == null || processExitCode == null || processExitCode < 0
                || errors == null || warnings == null) {
            return false;
        }
        boolean hasError = errors.stream().anyMatch(value -> value != null && !value.isBlank());
        boolean hasWarning = warnings.stream().anyMatch(value -> value != null && !value.isBlank());
        return switch (collectionOutcome) {
            case SUCCESS -> processExitCode == 0 && errors.isEmpty();
            case PARTIAL -> (hasError || hasWarning);
            case FAILED -> processExitCode > 0 && hasError;
        };
    }

    public String sha256Hex(ObjectMapper mapper) {
        try {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("attempt", attempt);
            envelope.put("schemaVersion", schemaVersion);
            if (jobType == null) envelope.putNull("jobType"); else envelope.put("jobType", jobType.name());
            if (collectionOutcome == null) envelope.putNull("collectionOutcome");
            else envelope.put("collectionOutcome", collectionOutcome.name());
            envelope.set("reportPayload", reportPayload == null
                    ? mapper.getNodeFactory().nullNode() : reportPayload);
            envelope.set("errors", mapper.valueToTree(errors));
            envelope.set("warnings", mapper.valueToTree(warnings));
            if (processExitCode == null) envelope.putNull("processExitCode");
            else envelope.put("processExitCode", processExitCode);

            byte[] canonicalJson = mapper.writeValueAsBytes(canonicalize(envelope, mapper));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize worker result envelope", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static JsonNode canonicalize(JsonNode node, ObjectMapper mapper) {
        if (node == null || node.isValueNode() || node.isNull()) return node;
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(child -> result.add(canonicalize(child, mapper)));
            return result;
        }
        Map<String, JsonNode> sorted = new TreeMap<>();
        node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
        ObjectNode result = mapper.createObjectNode();
        sorted.forEach((key, value) -> result.set(key, canonicalize(value, mapper)));
        return result;
    }
}
