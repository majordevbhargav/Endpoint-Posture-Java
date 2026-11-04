package com.endpointposture.job;

import com.endpointposture.job.spec.DiagnosticSpec;
import com.endpointposture.job.spec.HardwareSpec;
import com.endpointposture.job.spec.PostureSpec;
import com.endpointposture.job.spec.SecuritySpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentArgsTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void postureArgumentsPreserveLegacyOrderAndValues() {
        var spec = new PostureSpec(ID, "10.0.0.5", 60, 30, 15, 3,
                List.of("Cisco Secure Client"), List.of("uTorrent"));
        assertEquals(List.of("-ComputerName", "10.0.0.5", "-JobId", ID.toString(),
                "-PostureServer", "http://x/api/v1/posture", "-CimOperationTimeoutSec", "30",
                "-PostureServerTimeoutSec", "15", "-PolicyVersion", "3",
                "-RequiredAppsList", "Cisco Secure Client", "-BlockedAppsList", "uTorrent"),
                AgentArgs.of(spec, "http://x/api/v1/posture"));
    }

    @Test
    void postureOmitsEmptyPolicyLists() {
        var spec = new PostureSpec(ID, "10.0.0.5", 60, 30, 15, 3, List.of(), List.of());
        assertEquals(List.of("-ComputerName", "10.0.0.5", "-JobId", ID.toString(),
                "-PostureServer", "http://x/api/v1/posture", "-CimOperationTimeoutSec", "30",
                "-PostureServerTimeoutSec", "15", "-PolicyVersion", "3"),
                AgentArgs.of(spec, "http://x/api/v1/posture"));
    }

    @Test
    void hardwareArgumentsPreserveLegacyOrderAndValues() {
        var spec = new HardwareSpec(ID, "10.0.0.5", 70, 30, 20);
        assertEquals(List.of("-ComputerName", "10.0.0.5", "-JobId", ID.toString(),
                "-PostureAppBase", "http://x", "-CimOperationTimeoutSec", "30", "-SubmitTimeoutSec", "20"),
                AgentArgs.of(spec, "http://x"));
    }

    @Test
    void diagnosticArgumentsPreserveLegacyOrderAndValues() {
        var spec = new DiagnosticSpec(ID, "10.0.0.5", "AA:BB:CC:DD:EE:FF",
                130, 20, 70, 20, "www.microsoft.com", "8.8.8.8");
        assertEquals(List.of("-ComputerName", "10.0.0.5", "-Mac", "AA:BB:CC:DD:EE:FF",
                "-JobId", ID.toString(), "-PostureAppBase", "http://x",
                "-WinRmOpenTimeoutSec", "20", "-WinRmOperationTimeoutSec", "70",
                "-SubmitTimeoutSec", "20", "-DnsTestName", "www.microsoft.com", "-InternetTarget", "8.8.8.8"),
                AgentArgs.of(spec, "http://x"));
    }

    @Test
    void securityArgumentsPreserveLegacyOrderAndValues() {
        var spec = new SecuritySpec(ID, "10.0.0.5", "AA:BB:CC:DD:EE:FF",
                170, 20, 100, 20, 12, 5);
        assertEquals(List.of("-ComputerName", "10.0.0.5", "-Mac", "AA:BB:CC:DD:EE:FF",
                "-JobId", ID.toString(), "-PostureAppBase", "http://x",
                "-WinRmOpenTimeoutSec", "20", "-WinRmOperationTimeoutSec", "100",
                "-SubmitTimeoutSec", "20", "-SampleCount", "12", "-SampleIntervalSec", "5"),
                AgentArgs.of(spec, "http://x"));
    }
}
