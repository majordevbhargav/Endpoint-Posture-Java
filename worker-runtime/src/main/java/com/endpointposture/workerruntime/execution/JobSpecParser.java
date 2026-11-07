package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict parser for the backend's serialized JobSpec records. Unknown job types fail closed. */
public final class JobSpecParser {
    private static final Pattern MAC = Pattern.compile("(?i)^[0-9a-f]{2}([-:])[0-9a-f]{2}(?:\\1[0-9a-f]{2}){4}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,252}$");
    private final URI backend;

    public JobSpecParser(URI backend) {
        this.backend = backend;
    }

    public ValidatedJob parse(ClaimedJob claimed) {
        if (claimed == null || claimed.jobId() == null || claimed.attempt() < 1
                || claimed.leaseExpiresAt() == null || claimed.spec() == null || !claimed.spec().isObject()) {
            throw new IllegalArgumentException("Claimed job envelope is incomplete");
        }
        JsonNode s = claimed.spec();
        String type = requiredText(claimed.jobType(), "jobType").toUpperCase(Locale.ROOT);
        UUID specId = UUID.fromString(requiredText(s.path("jobId").asText(null), "spec.jobId"));
        if (!specId.equals(claimed.jobId())) throw new IllegalArgumentException("Job ID does not match the typed spec");
        String target = host(s.path("target").asText(null), "target");
        int timeout = positive(s, "processTimeoutSeconds");
        List<String> args = new ArrayList<>();
        String script;
        switch (type) {
            case "POSTURE_CHECK" -> {
                script = "posture_agent.ps1";
                add(args, "-ComputerName", target);
                add(args, "-JobId", claimed.jobId().toString());
                add(args, "-PostureServer", endpoint("/api/v1/posture"));
                add(args, "-CimOperationTimeoutSec", positive(s, "cimTimeoutSec"));
                add(args, "-PostureServerTimeoutSec", positive(s, "serverTimeoutSec"));
                int version = positive(s, "policyVersion");
                add(args, "-PolicyVersion", version);
                addList(args, "-RequiredAppsList", s.path("requiredApps"));
                addList(args, "-BlockedAppsList", s.path("blockedApps"));
            }
            case "HARDWARE_CHECK" -> {
                script = "hardware_health_agent.ps1";
                add(args, "-ComputerName", target);
                add(args, "-JobId", claimed.jobId().toString());
                add(args, "-PostureAppBase", backend.toString());
                add(args, "-CimOperationTimeoutSec", positive(s, "cimTimeoutSec"));
                add(args, "-SubmitTimeoutSec", positive(s, "submitTimeoutSec"));
            }
            case "DIAGNOSTIC_CHECK" -> {
                script = "diagnostic_agent.ps1";
                add(args, "-ComputerName", target);
                add(args, "-Mac", mac(s.path("mac").asText(null)));
                add(args, "-JobId", claimed.jobId().toString());
                add(args, "-PostureAppBase", backend.toString());
                add(args, "-WinRmOpenTimeoutSec", positive(s, "winrmOpenTimeoutSec"));
                add(args, "-WinRmOperationTimeoutSec", positive(s, "winrmOperationTimeoutSec"));
                add(args, "-SubmitTimeoutSec", positive(s, "submitTimeoutSec"));
                add(args, "-DnsTestName", host(s.path("dnsTestName").asText(null), "dnsTestName"));
                add(args, "-InternetTarget", host(s.path("internetTarget").asText(null), "internetTarget"));
            }
            case "SECURITY_CHECK" -> {
                script = "security_indicator_agent.ps1";
                add(args, "-ComputerName", target);
                add(args, "-Mac", mac(s.path("mac").asText(null)));
                add(args, "-JobId", claimed.jobId().toString());
                add(args, "-PostureAppBase", backend.toString());
                add(args, "-WinRmOpenTimeoutSec", positive(s, "winrmOpenTimeoutSec"));
                add(args, "-WinRmOperationTimeoutSec", positive(s, "winrmOperationTimeoutSec"));
                add(args, "-SubmitTimeoutSec", positive(s, "submitTimeoutSec"));
                add(args, "-SampleCount", positive(s, "sampleCount"));
                add(args, "-SampleIntervalSec", positive(s, "sampleIntervalSec"));
            }
            default -> throw new IllegalArgumentException("Unsupported worker job type: " + type);
        }
        args.add("-OutputOnly");
        return new ValidatedJob(claimed.jobId(), claimed.attempt(), type, script, args, Duration.ofSeconds(timeout));
    }

    private String endpoint(String path) { return backend.resolve(path).toString(); }

    private static int positive(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1)
            throw new IllegalArgumentException("spec." + name + " must be a positive integer");
        return value.intValue();
    }

    private static String host(String value, String name) {
        String text = requiredText(value, name);
        if (!HOST.matcher(text).matches() || text.contains("..") || text.startsWith("-"))
            throw new IllegalArgumentException("spec." + name + " is not a valid host/name");
        return text;
    }

    private static String mac(String value) {
        String text = requiredText(value, "mac");
        if (!MAC.matcher(text).matches()) throw new IllegalArgumentException("spec.mac is invalid");
        return text;
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 512)
            throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static void add(List<String> args, String key, Object value) {
        args.add(key);
        args.add(String.valueOf(value));
    }

    private static void addList(List<String> args, String key, JsonNode values) {
        if (values == null || values.isMissingNode() || values.isNull()) return;
        if (!values.isArray() || values.size() > 200) throw new IllegalArgumentException("spec." + key + " must be an array of at most 200 strings");
        List<String> checked = new ArrayList<>();
        for (JsonNode item : values) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 120
                    || item.asText().contains("|") || item.asText().indexOf('"') >= 0 || item.asText().chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("spec." + key + " contains an invalid item");
            checked.add(item.asText());
        }
        if (!checked.isEmpty()) add(args, key, String.join("|", checked));
    }
}