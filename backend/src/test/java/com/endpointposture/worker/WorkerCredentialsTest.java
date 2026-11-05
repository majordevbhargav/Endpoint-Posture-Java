package com.endpointposture.worker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkerCredentialsTest {
    @Test void keysAndTokensHaveTheirOwnShapeAndAreUnique() {
        String key=WorkerCredentials.newKey(), token=WorkerCredentials.newEnrollmentToken();
        assertTrue(WorkerCredentials.looksLikeKey(key));
        assertFalse(WorkerCredentials.looksLikeToken(key));
        assertTrue(WorkerCredentials.looksLikeToken(token));
        assertNotEquals(key,WorkerCredentials.newKey());
    }
    @Test void malformedOrOversizedInputIsRejectedBeforeLookup() {
        assertFalse(WorkerCredentials.looksLikeKey(null));
        assertFalse(WorkerCredentials.looksLikeKey("epw_"));
        assertFalse(WorkerCredentials.looksLikeKey("x".repeat(500)));
    }
    @Test void hashIsStableAndDoesNotContainSecret() {
        String key=WorkerCredentials.newKey();
        assertEquals(WorkerCredentials.sha256Hex(key),WorkerCredentials.sha256Hex(key));
        assertEquals(64,WorkerCredentials.sha256Hex(key).length());
        assertFalse(WorkerCredentials.sha256Hex(key).contains(key));
    }
}
