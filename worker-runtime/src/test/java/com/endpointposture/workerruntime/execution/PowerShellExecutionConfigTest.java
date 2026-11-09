package com.endpointposture.workerruntime.execution;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PowerShellExecutionConfigTest {
    @Test void executionIsDisabledByDefault() {
        var c=PowerShellExecutionConfig.fromEnvironment(Map.of());
        assertFalse(c.enabled());
        assertEquals("powershell.exe",c.executable());
        assertEquals(Duration.ofSeconds(15),c.leaseSafetyMargin());
    }
    @Test void rejectsInvalidOutputAndNegativeMargin() {
        assertThrows(IllegalArgumentException.class,()->new PowerShellExecutionConfig(true,"powershell.exe",Path.of("."),Duration.ofSeconds(1),0));
        assertThrows(IllegalArgumentException.class,()->new PowerShellExecutionConfig(true,"powershell.exe",Path.of("."),Duration.ofSeconds(-1),1024));
    }
}