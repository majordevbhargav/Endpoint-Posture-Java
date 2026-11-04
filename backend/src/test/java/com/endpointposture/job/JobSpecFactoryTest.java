package com.endpointposture.job;

import com.endpointposture.diagnostic.config.DiagnosticAgentProperties;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.indicator.config.SecurityIndicatorAgentProperties;
import com.endpointposture.job.spec.*;
import com.endpointposture.policy.PolicyService;
import com.endpointposture.posture.config.PostureAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobSpecFactoryTest {
    private PolicyService policyService;
    private JobSpecFactory factory;

    @BeforeEach
    void setUp() {
        policyService = mock(PolicyService.class);
        factory = new JobSpecFactory(new PostureAgentProperties(), new HardwareAgentProperties(),
                new DiagnosticAgentProperties(), new SecurityIndicatorAgentProperties(), policyService);
        when(policyService.getActive()).thenReturn(new PolicyService.PolicySnapshot(
                UUID.randomUUID(), "active", 4, List.of("Required App"), List.of("Blocked App"), "test", Instant.now()));
    }

    @Test
    void buildsPostureSpecWithActivePolicy() {
        var spec = assertInstanceOf(PostureSpec.class, factory.build(job(JobType.POSTURE_CHECK), endpoint("10.0.0.5", "host01")));
        assertEquals("10.0.0.5", spec.target());
        assertEquals(4, spec.policyVersion());
        assertEquals(List.of("Required App"), spec.requiredApps());
        assertEquals(List.of("Blocked App"), spec.blockedApps());
    }

    @Test
    void buildsHardwareSpec() {
        var spec = assertInstanceOf(HardwareSpec.class, factory.build(job(JobType.HARDWARE_CHECK), endpoint("10.0.0.5", "host01")));
        assertEquals(30, spec.cimTimeoutSec());
        verifyNoInteractions(policyService);
    }

    @Test
    void buildsDiagnosticSpec() {
        var spec = assertInstanceOf(DiagnosticSpec.class, factory.build(job(JobType.DIAGNOSTIC_CHECK), endpoint(null, "host01")));
        assertEquals("host01", spec.target());
        assertEquals("AA:BB:CC:DD:EE:FF", spec.mac());
        verifyNoInteractions(policyService);
    }

    @Test
    void buildsSecuritySpec() {
        var spec = assertInstanceOf(SecuritySpec.class, factory.build(job(JobType.SECURITY_CHECK), endpoint("10.0.0.5", "host01")));
        assertEquals(12, spec.sampleCount());
        assertEquals(5, spec.sampleIntervalSec());
        verifyNoInteractions(policyService);
    }

    @Test
    void prefersIpAndFallsBackToHostname() {
        assertEquals("10.0.0.5", factory.build(job(JobType.HARDWARE_CHECK), endpoint("10.0.0.5", "host01")).target());
        assertEquals("host01", factory.build(job(JobType.HARDWARE_CHECK), endpoint(null, "host01")).target());
    }

    @Test
    void rejectsEndpointWithoutIpOrHostname() {
        assertThrows(IllegalStateException.class,
                () -> factory.build(job(JobType.HARDWARE_CHECK), endpoint(null, null)));
    }

    private static PostureJob job(JobType type) {
        return PostureJob.builder().id(UUID.randomUUID()).jobType(type).build();
    }

    private static Endpoint endpoint(String ip, String hostname) {
        return Endpoint.builder().id(UUID.randomUUID()).macAddress("AA:BB:CC:DD:EE:FF")
                .ipAddress(ip).hostname(hostname).build();
    }
}
