package com.endpointposture.workerruntime.api;

import com.endpointposture.workerruntime.WorkerConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Typed client for /api/v1/workers/**. Redirects are never followed, so the key cannot be
 * forwarded to another host. Response bodies are size-limited and exceptions never include keys.
 */
public final class WorkerApiClient {
    public static final String KEY_HEADER = "X-Worker-Key";
    private static final String BASE_PATH = "/api/v1/workers";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private record ApiResponse(int statusCode, HttpHeaders headers, String body) {}

    private final HttpClient http;
    private final URI base;
    private final Duration requestTimeout;
    private final String apiKey;
    private final ObjectMapper mapper = Json.mapper();

    private WorkerApiClient(WorkerConfig config, String apiKey) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.base = config.backendUrl();
        this.requestTimeout = config.requestTimeout();
        this.apiKey = apiKey;
    }

    /** For enrollment only. */
    public static WorkerApiClient unauthenticated(WorkerConfig config) {
        return new WorkerApiClient(config, null);
    }

    public static WorkerApiClient authenticated(WorkerConfig config, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey is required");
        return new WorkerApiClient(config, apiKey);
    }

    public Enrolled enroll(String token) {
        ApiResponse res = send(post("/enroll", Map.of("token", token), false));
        if (res.statusCode() == 401) throw new WorkerApiException.EnrollmentRejected(message(res));
        check(res);
        return read(res, Enrolled.class);
    }

    public HeartbeatResponse heartbeat() {
        ApiResponse res = send(post("/heartbeat", null, true));
        check(res);
        return read(res, HeartbeatResponse.class);
    }

    /** @return the claimed job, or empty when nothing is eligible (204) */
    public Optional<ClaimedJob> claim() {
        ApiResponse res = send(post("/jobs/claim", null, true));
        if (res.statusCode() == 204) return Optional.empty();
        check(res);
        return Optional.of(read(res, ClaimedJob.class));
    }

    /**
     * @param extensionSeconds null uses the server's lease length
     * @return the new lease expiry
     * @throws WorkerApiException.StaleAttempt when the lease is stale, expired or not ours
     */
    public Instant renewLease(UUID jobId, int attempt, Integer extensionSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attempt", attempt);
        if (extensionSeconds != null) body.put("extensionSeconds", extensionSeconds);
        ApiResponse res = send(post("/jobs/" + jobId + "/lease/renew", body, true));
        check(res);
        try {
            JsonNode expiry = mapper.readTree(res.body()).get("leaseExpiresAt");
            if (expiry == null || expiry.isNull()) throw new IllegalArgumentException("missing");
            return mapper.convertValue(expiry, Instant.class);
        } catch (IOException | IllegalArgumentException e) {
            throw new WorkerApiException(res.statusCode(), "Unexpected lease renewal response from server");
        }
    }

    public void complete(UUID jobId, int attempt) {
        check(send(post("/jobs/" + jobId + "/complete", Map.of("attempt", attempt), true)));
    }

    public void fail(UUID jobId, int attempt, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attempt", attempt);
        if (reason != null) body.put("reason", reason);
        check(send(post("/jobs/" + jobId + "/fail", body, true)));
    }

    private HttpRequest post(String path, Object body, boolean auth) {
        if (auth && apiKey == null) throw new IllegalStateException("This call needs an authenticated client");
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(BASE_PATH + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (auth) b.header(KEY_HEADER, apiKey);
        if (body == null) {
            b.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            try {
                b.header("Content-Type", "application/json");
                b.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Cannot serialize request", e);
            }
        }
        return b.build();
    }

    private ApiResponse send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES)
                    throw new WorkerTransportException("Worker API response exceeded the maximum allowed size of "
                            + MAX_RESPONSE_BYTES + " bytes");
                return new ApiResponse(response.statusCode(), response.headers(),
                        new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerTransportException("Interrupted while calling the worker API", e);
        } catch (IOException e) {
            throw new WorkerTransportException("Cannot read response from " + base + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    private void check(ApiResponse res) {
        int s = res.statusCode();
        if (s / 100 == 2) return;
        String msg = message(res);
        throw switch (s) {
            case 401 -> new WorkerApiException.Unauthorized(msg);
            case 409 -> new WorkerApiException.StaleAttempt(msg);
            case 429 -> new WorkerApiException.RateLimited(msg, retryAfter(res));
            default -> new WorkerApiException(s, msg);
        };
    }

    private <T> T read(ApiResponse res, Class<T> type) {
        try {
            return mapper.readValue(res.body(), type);
        } catch (JsonProcessingException e) {
            throw new WorkerApiException(res.statusCode(), "Unexpected response from server");
        }
    }

    private String message(ApiResponse res) {
        String body = res.body() == null ? "" : res.body().trim();
        String text = body;
        if (body.startsWith("{")) {
            try {
                JsonNode m = mapper.readTree(body).get("message");
                if (m != null && m.isTextual()) text = m.asText();
            } catch (IOException ignored) {
                // fall back to the raw text
            }
        }
        if (text.isBlank()) text = "HTTP " + res.statusCode();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static Duration retryAfter(ApiResponse res) {
        return res.headers().firstValue("Retry-After").map(v -> {
            try {
                return Duration.ofSeconds(Math.max(0, Long.parseLong(v.trim())));
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }

    @Override
    public String toString() {
        return "WorkerApiClient[base=" + base + ", authenticated=" + (apiKey != null) + "]";
    }
}
