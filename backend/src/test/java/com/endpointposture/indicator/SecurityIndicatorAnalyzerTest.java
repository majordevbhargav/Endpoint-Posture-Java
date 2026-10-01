package com.endpointposture.indicator;

import com.endpointposture.indicator.EndpointSecurityIndicator.RiskLevel;
import com.endpointposture.indicator.dto.SecurityReportRequest.Connection;
import com.endpointposture.indicator.dto.SecurityReportRequest.Sample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecurityIndicatorAnalyzerTest {

    private final SecurityIndicatorAnalyzer analyzer = new SecurityIndicatorAnalyzer();

    private Connection c(String addr, int rport, int lport) { return new Connection(addr, rport, lport, 1, "proc"); }
    private Sample s(int i, Connection... cs) { return new Sample(i, i * 5.0, List.of(cs)); }

    @Test
    void quietHostHasNoFindings() {
        var r = analyzer.analyze(List.of(s(0, c("8.8.8.8", 443, 50000))), 5);
        assertEquals(RiskLevel.NONE, r.risk());
        assertTrue(r.findings().isEmpty());
    }

    @Test
    void fanOutOnSmbToFiveHostsIsMedium() {
        List<Connection> cs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) cs.add(c("10.0.0." + i, 445, 50000 + i));
        var r = analyzer.analyze(List.of(new Sample(0, 0.0, cs)), 5);
        assertEquals(RiskLevel.MEDIUM, r.risk());
        assertEquals("LATERAL_MOVEMENT", r.findings().get(0).get("type"));
    }

    @Test
    void fanOutToTenHostsIsHigh() {
        List<Connection> cs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) cs.add(c("192.168.1." + i, 3389, 50000 + i));
        assertEquals(RiskLevel.HIGH, analyzer.analyze(List.of(new Sample(0, 0.0, cs)), 5).risk());
    }

    @Test
    void inboundAdminTrafficToLocalListenerIsNotFanOut() {
        List<Connection> cs = new ArrayList<>();
        for (int i = 1; i <= 8; i++) cs.add(c("10.0.0." + i, 445, 445)); // local port is 445 too: inbound
        assertEquals(RiskLevel.NONE, analyzer.analyze(List.of(new Sample(0, 0.0, cs)), 5).risk());
    }

    @Test
    void regularReconnectsToTheSameExternalHostAreFlaggedAsBeaconing() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // present on odd samples only: re-opens every 10 s
            samples.add(i % 2 == 1 ? s(i, c("203.0.113.9", 8443, 50000 + i)) : s(i));
        }
        var r = analyzer.analyze(samples, 5);
        assertTrue(r.findings().stream().anyMatch(f -> "BEACONING".equals(f.get("type"))));
    }

    @Test
    void aPersistentConnectionIsNotBeaconing() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 12; i++) samples.add(s(i, c("203.0.113.9", 443, 50000)));
        assertEquals(RiskLevel.NONE, analyzer.analyze(samples, 5).risk());
    }

    @Test
    void tooFewSamplesSkipsBeaconAnalysis() {
        var r = analyzer.analyze(List.of(s(0), s(1)), 5);
        assertEquals(false, r.summary().get("beaconingAnalyzed"));
    }

    @Test
    void suspectExternalPortIsMedium() {
        var r = analyzer.analyze(List.of(s(0, c("203.0.113.9", 4444, 50000))), 5);
        assertEquals(RiskLevel.MEDIUM, r.risk());
    }

    @Test
    void loopbackAndLinkLocalAreIgnored() {
        var r = analyzer.analyze(List.of(s(0, c("127.0.0.1", 4444, 1), c("169.254.1.1", 445, 2))), 5);
        assertEquals(RiskLevel.NONE, r.risk());
    }
}