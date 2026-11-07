package com.endpointposture.workerruntime.api;

import java.util.UUID;

/** Result of redeeming an enrollment token. The API key is shown by the server exactly once. */
public record Enrolled(UUID workerId, String name, String shard, String apiKey) {
    @Override
    public String toString() {
        return "Enrolled[workerId=" + workerId + ", name=" + name + ", shard=" + shard + ", apiKey=<redacted>]";
    }
}