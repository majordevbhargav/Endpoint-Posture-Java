package com.endpointposture.workerruntime.credentials;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/** What an enrolled worker keeps: identity, the backend it enrolled with, and its API key. */
public record StoredCredentials(UUID workerId, String name, String shard, URI backendUrl, String apiKey) {
    private static final String KEY_PREFIX = "epw_";
    private static final int MAX_KEY_LENGTH = 128;

    public StoredCredentials {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(shard, "shard");
        Objects.requireNonNull(backendUrl, "backendUrl");
        if (!looksLikeKey(apiKey)) throw new IllegalArgumentException("apiKey does not look like a worker key");
    }

    /** Same shape rule as the server: "epw_" prefix, at most 128 characters. */
    public static boolean looksLikeKey(String v) {
        return v != null && v.length() > KEY_PREFIX.length() && v.length() <= MAX_KEY_LENGTH && v.startsWith(KEY_PREFIX);
    }

    @Override
    public String toString() {
        return "StoredCredentials[workerId=" + workerId + ", name=" + name + ", shard=" + shard
                + ", backendUrl=" + backendUrl + ", apiKey=<redacted>]";
    }
}