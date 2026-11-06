package com.endpointposture.workerruntime.credentials;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

class DpapiSecretProtectorTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void roundTripsThroughRealDpapi() {
        DpapiSecretProtector p = new DpapiSecretProtector();
        String protectedValue = p.protect("epw_round-trip-secret");

        assertNotEquals("epw_round-trip-secret", protectedValue);
        assertFalse(protectedValue.contains("round-trip"));
        assertEquals("epw_round-trip-secret", p.unprotect(protectedValue));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void garbageCannotBeUnprotected() {
        assertThrows(SecretProtectionException.class, () -> new DpapiSecretProtector().unprotect("not-a-dpapi-blob"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void otherPlatformsFailClearly() {
        var ex = assertThrows(SecretProtectionException.class, () -> new DpapiSecretProtector().protect("x"));
        assertTrue(ex.getMessage().contains("Windows"));
    }
}