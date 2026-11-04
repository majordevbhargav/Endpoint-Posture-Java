package com.endpointposture.job.spec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobSpecValidationTest {
    private static final UUID ID = UUID.randomUUID();

    @Test
    void rejectsUnsafeOrEmptyTargets() {
        for (String target : List.of("-evil", "a b", "host;calc", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new HardwareSpec(ID, target, 70, 30, 20), target);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new HardwareSpec(ID, null, 70, 30, 20));
    }

    @Test
    void acceptsIpAndSimulatorHostnameTargets() {
        assertEquals("10.0.195.80", new HardwareSpec(ID, "10.0.195.80", 70, 30, 20).target());
        assertEquals("SIM-00001", new HardwareSpec(ID, "SIM-00001", 70, 30, 20).target());
    }

    @Test
    void rejectsInvalidMacAddress() {
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosticSpec(ID, "host01", "ZZ:00:00:00:00:00",
                        130, 20, 70, 20, "www.microsoft.com", "8.8.8.8"));
    }

    @Test
    void rejectsUnsafePolicyPatterns() {
        assertThrows(IllegalArgumentException.class,
                () -> new PostureSpec(ID, "host01", 60, 30, 15, 1, List.of("bad|pattern"), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PostureSpec(ID, "host01", 60, 30, 15, 1, List.of(), List.of("bad\"pattern")));
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThrows(IllegalArgumentException.class,
                () -> new HardwareSpec(ID, "host01", 0, 30, 20));
    }

    @Test
    void rejectsMissingJobId() {
        assertThrows(NullPointerException.class, () -> new HardwareSpec(null, "host01", 70, 30, 20));
    }
}
