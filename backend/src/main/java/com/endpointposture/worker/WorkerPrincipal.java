package com.endpointposture.worker;
import java.util.UUID;
/** Authenticated worker identity, derived only from its registered credential. */
public record WorkerPrincipal(UUID id, String name, String shard) {
    public String claimId() { return id.toString(); }
}