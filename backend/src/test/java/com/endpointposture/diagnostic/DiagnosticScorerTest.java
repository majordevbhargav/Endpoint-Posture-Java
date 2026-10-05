package com.endpointposture.diagnostic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the deduction model. The thresholds are illustrative, so a change should be a visible diff. */
class DiagnosticScorerTest {

    private final DiagnosticScorer scorer = new DiagnosticScorer();

    private Map<String, Object> goodGateway() { return Map.of("reachable", true, "avgMs", 2.0, "lossPct", 0); }
    private Map<String, Object> goodDns()     { return Map.of("resolved", true, "ms", 20); }
    private Map<String, Object> goodInternet(){ return Map.of("reachable", true, "avgMs", 20.0, "lossPct", 0); }
    private Map<String, Object> goodTcp()     { return Map.of("connected", true, "ms", 50); }

    @Test
    void everythingHealthyScoresHundredWithNoDeductions() {
        DiagnosticScorer.Result r = scorer.score(goodGateway(), goodDns(), goodInternet(), goodTcp());
        assertEquals(100, r.score());
        assertEquals(EndpointDiagnostic.Band.HEALTHY, r.band());
        assertTrue(r.deductions().isEmpty());
    }

    @Test
    void sectionsTheAgentDidNotReportAreNotPenalised() {
        DiagnosticScorer.Result r = scorer.score(null, null, null, null);
        assertEquals(100, r.score());
        assertTrue(r.deductions().isEmpty());
    }

    @Test
    void gatewayDownCostsForty() {
        DiagnosticScorer.Result r = scorer.score(Map.of("reachable", false), goodDns(), goodInternet(), goodTcp());
        assertEquals(60, r.score());
        assertEquals(EndpointDiagnostic.Band.DEGRADED, r.band());
        assertEquals("GATEWAY", r.deductions().get(0).get("check"));
        assertEquals(40, r.deductions().get(0).get("points"));
    }

    @Test
    void slowGatewayAndPacketLossBothDeduct() {
        // 120 ms -> 20; 50% loss -> capped at 20.
        DiagnosticScorer.Result r = scorer.score(
                Map.of("reachable", true, "avgMs", 120.0, "lossPct", 50), goodDns(), goodInternet(), goodTcp());
        assertEquals(60, r.score());
        assertEquals(2, r.deductions().size());
    }

    @Test
    void dnsAndTcpFailureTogetherIsStillDegraded() {
        DiagnosticScorer.Result r = scorer.score(
                goodGateway(), Map.of("resolved", false), goodInternet(), Map.of("connected", false));
        assertEquals(45, r.score()); // 100 - 25 - 30
        assertEquals(EndpointDiagnostic.Band.CRITICAL, r.band());
    }

    @Test
    void slowDnsAndSlowTcpDeductTenEach() {
        DiagnosticScorer.Result r = scorer.score(
                goodGateway(), Map.of("resolved", true, "ms", 300), goodInternet(), Map.of("connected", true, "ms", 800));
        assertEquals(80, r.score());
        assertEquals(EndpointDiagnostic.Band.WARNING, r.band());
    }

    @Test
    void scoreNeverGoesBelowZero() {
        DiagnosticScorer.Result r = scorer.score(
                Map.of("reachable", false), Map.of("resolved", false),
                Map.of("reachable", false), Map.of("connected", false));
        assertEquals(0, r.score()); // 40 + 25 + 10 + 30 = 105
        assertEquals(EndpointDiagnostic.Band.CRITICAL, r.band());
    }

    @Test
    void stringBooleansFromTheAgentAreParsed() {
        DiagnosticScorer.Result r = scorer.score(Map.of("reachable", "false"), null, null, null);
        assertEquals(60, r.score());
    }

    @Test
    void bandBoundaries() {
        assertEquals(EndpointDiagnostic.Band.HEALTHY, DiagnosticScorer.bandFor(85));
        assertEquals(EndpointDiagnostic.Band.WARNING, DiagnosticScorer.bandFor(84));
        assertEquals(EndpointDiagnostic.Band.WARNING, DiagnosticScorer.bandFor(70));
        assertEquals(EndpointDiagnostic.Band.DEGRADED, DiagnosticScorer.bandFor(69));
        assertEquals(EndpointDiagnostic.Band.DEGRADED, DiagnosticScorer.bandFor(50));
        assertEquals(EndpointDiagnostic.Band.CRITICAL, DiagnosticScorer.bandFor(49));
    }
}