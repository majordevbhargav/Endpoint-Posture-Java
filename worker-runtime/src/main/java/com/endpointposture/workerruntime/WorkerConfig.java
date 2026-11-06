package com.endpointposture.workerruntime;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Worker settings. The enrollment token is read from the environment (never a command-line
 * argument, where it would show in process listings) and is redacted from toString.
 */
public record WorkerConfig(URI backendUrl, Path stateFile, String enrollmentToken,
                           Duration connectTimeout, Duration requestTimeout) {

    public WorkerConfig {
        Objects.requireNonNull(backendUrl, "backendUrl");
        Objects.requireNonNull(stateFile, "stateFile");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        backendUrl = normalize(backendUrl);
        if (enrollmentToken != null && enrollmentToken.isBlank()) enrollmentToken = null;
        if (connectTimeout.isZero() || connectTimeout.isNegative())
            throw new IllegalArgumentException("connect timeout must be positive");
        if (requestTimeout.isZero() || requestTimeout.isNegative())
            throw new IllegalArgumentException("request timeout must be positive");
    }

    /** Reads EP_BACKEND_URL (required), EP_WORKER_STATE_FILE, EP_ENROLLMENT_TOKEN and the two timeouts. */
    public static WorkerConfig fromEnvironment(Map<String, String> env) {
        String url = trimToNull(env.get("EP_BACKEND_URL"));
        if (url == null) throw new IllegalArgumentException("EP_BACKEND_URL is required");
        String stateRaw = trimToNull(env.get("EP_WORKER_STATE_FILE"));
        Path state = stateRaw != null ? Path.of(stateRaw) : defaultStateFile(env);
        return new WorkerConfig(
                URI.create(url),
                state,
                env.get("EP_ENROLLMENT_TOKEN"),
                seconds(env, "EP_HTTP_CONNECT_TIMEOUT_SECONDS", 10),
                seconds(env, "EP_HTTP_REQUEST_TIMEOUT_SECONDS", 30));
    }

    static Path defaultStateFile(Map<String, String> env) {
        String programData = trimToNull(env.get("ProgramData"));
        if (programData != null) return Path.of(programData, "EndpointPostureWorker", "state.json");
        return Path.of(System.getProperty("user.home"), ".endpoint-posture-worker", "state.json");
    }

    private static URI normalize(URI u) {
        String scheme = u.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")))
            throw new IllegalArgumentException("EP_BACKEND_URL must start with http:// or https://");
        if (u.getHost() == null) throw new IllegalArgumentException("EP_BACKEND_URL must include a host");
        if (u.getUserInfo() != null)
            throw new IllegalArgumentException("EP_BACKEND_URL must not contain a user name or password");
        String path = u.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/"))
            throw new IllegalArgumentException("EP_BACKEND_URL must not include a path");
        if (u.getQuery() != null || u.getFragment() != null)
            throw new IllegalArgumentException("EP_BACKEND_URL must not include a query or fragment");
        if (scheme.equalsIgnoreCase("http") && !isLoopbackHost(u.getHost())) {
            throw new IllegalArgumentException("EP_BACKEND_URL must use HTTPS unless the host is localhost or 127.0.0.1 for local development");
        }
        return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT)
                + (u.getPort() == -1 ? "" : ":" + u.getPort()));
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }
    }

    private static Duration seconds(Map<String, String> env, String name, long fallback) {
        String raw = trimToNull(env.get(name));
        if (raw == null) return Duration.ofSeconds(fallback);
        try {
            return Duration.ofSeconds(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number of seconds");
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Override
    public String toString() {
        return "WorkerConfig[backendUrl=" + backendUrl + ", stateFile=" + stateFile
                + ", enrollmentToken=" + (enrollmentToken == null ? "<none>" : "<redacted>")
                + ", connectTimeout=" + connectTimeout + ", requestTimeout=" + requestTimeout + "]";
    }
}