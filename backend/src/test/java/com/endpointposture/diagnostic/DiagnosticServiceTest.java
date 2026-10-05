package com.endpointposture.diagnostic;

import com.endpointposture.diagnostic.dto.DiagnosticReportRequest;
import com.endpointposture.diagnostic.dto.DiagnosticReportRequest.EndpointDto;
import com.endpointposture.diagnostic.dto.DiagnosticResponse;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the diagnostic write path: a run that measured nothing still leaves a row,
 * its score is null (never zero), and bad agent input is rejected before anything is saved.
 * Uses the real DiagnosticScorer; only persistence and the endpoint upsert are mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiagnosticServiceTest {

    static final String MAC = "AA:BB:CC:DD:EE:FF";

    @Mock EndpointDiagnosticRepository repository;
    @Mock EndpointService endpointService;

    DiagnosticService service;
    final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DiagnosticService(repository, endpointService, new DiagnosticScorer());

        Endpoint endpoint = Endpoint.builder().id(endpointId).macAddress(MAC).build();
        when(endpointService.upsertByMac(any(), any(), any(), any(), any())).thenReturn(endpoint);
        when(repository.save(any())).thenAnswer(i -> {
            EndpointDiagnostic d = i.getArgument(0);
            d.setId(UUID.randomUUID());
            d.setCollectedAt(Instant.now());
            return d;
        });
    }

    // ---- helpers -----------------------------------------------------------------

    private Map<String, Object> goodGateway()  { return Map.of("reachable", true, "avgMs", 2.0, "lossPct", 0); }
    private Map<String, Object> goodDns()      { return Map.of("resolved", true, "ms", 20); }
    private Map<String, Object> goodInternet() { return Map.of("reachable", true, "avgMs", 20.0, "lossPct", 0); }
    private Map<String, Object> goodTcp()      { return Map.of("connected", true, "ms", 50); }

    private DiagnosticReportRequest report(String status, String detail,
                                           Map<String, Object> gw, Map<String, Object> dns,
                                           Map<String, Object> net, Map<String, Object> tcp) {
        return new DiagnosticReportRequest(null, new EndpointDto(MAC, "host", "10.0.0.5"),
                status, detail, gw, dns, net, tcp, null);
    }

    private DiagnosticReportRequest okReport() {
        return report("OK", null, goodGateway(), goodDns(), goodInternet(), goodTcp());
    }

    private DiagnosticReportRequest withMac(EndpointDto endpoint) {
        return new DiagnosticReportRequest(null, endpoint, "OK", null,
                goodGateway(), goodDns(), goodInternet(), goodTcp(), null);
    }

    private EndpointDiagnostic saved() {
        ArgumentCaptor<EndpointDiagnostic> c = ArgumentCaptor.forClass(EndpointDiagnostic.class);
        verify(repository).save(c.capture());
        return c.getValue();
    }

    // ---- ingest: happy paths -----------------------------------------------------

    @Test
    void healthyReportScoresHundredWithNoDeductions() {
        DiagnosticResponse r = service.ingest(okReport());

        assertEquals(EndpointDiagnostic.Status.OK, r.status());
        assertEquals(100, r.score());
        assertEquals(EndpointDiagnostic.Band.HEALTHY, r.band());
        assertNotNull(r.deductions());
        assertTrue(r.deductions().isEmpty());
        assertNull(r.errorMessage());
    }

    @Test
    void gatewayDownIsStoredWithScoreBandAndTheReasonPointsWereLost() {
        DiagnosticResponse r = service.ingest(
                report("OK", null, Map.of("reachable", false), goodDns(), goodInternet(), goodTcp()));

        assertEquals(60, r.score());
        assertEquals(EndpointDiagnostic.Band.DEGRADED, r.band());
        assertEquals(1, r.deductions().size());
        assertEquals("GATEWAY", r.deductions().get(0).get("check"));
        assertEquals(40, r.deductions().get(0).get("points"));

        EndpointDiagnostic row = saved();
        assertEquals(EndpointDiagnostic.Status.OK, row.getStatus());
        assertEquals(60, row.getScore());
        assertNull(row.getErrorMessage());
    }

    @Test
    void reportIsAttachedToTheEndpointByMacWithIpAndHostname() {
        service.ingest(okReport());
        verify(endpointService).upsertByMac(MAC, "10.0.0.5", "host", null, null);
        assertEquals(endpointId, saved().getEndpointId());
    }

    @Test
    void anOkReportWithOnlyOneProbeIsAccepted() {
        DiagnosticResponse r = service.ingest(report("OK", null, goodGateway(), null, null, null));
        assertEquals(100, r.score());
    }

    @Test
    void rawReportKeepsEveryProbeKeyEvenWhenTheAgentOmittedSome() {
        service.ingest(report("OK", null, goodGateway(), null, null, null));

        Map<String, Object> raw = saved().getRawReport();
        assertEquals("OK", raw.get("status"));
        assertNotNull(raw.get("gateway"));
        // Map.of would have thrown on these nulls; the service uses a HashMap on purpose.
        assertTrue(raw.containsKey("dns"));
        assertNull(raw.get("dns"));
        assertTrue(raw.containsKey("traceroute"));
    }

    @Test
    void statusIsCaseInsensitiveAndTrimmed() {
        DiagnosticResponse r = service.ingest(report("  ok ", null, goodGateway(), goodDns(), goodInternet(), goodTcp()));
        assertEquals(EndpointDiagnostic.Status.OK, r.status());
    }

    // ---- ingest: WinRM unavailable -----------------------------------------------

    @Test
    void winrmUnavailableIsAdistinctResultWithNullScoreNeverZero() {
        DiagnosticResponse r = service.ingest(
                report("WINRM_UNAVAILABLE", "WinRM refused", null, null, null, null));

        assertEquals(EndpointDiagnostic.Status.WINRM_UNAVAILABLE, r.status());
        assertNull(r.score());
        assertNull(r.band());
        assertNull(r.deductions());
        assertEquals("WinRM refused", r.errorMessage());

        EndpointDiagnostic row = saved();
        assertNull(row.getScore());
        assertNull(row.getBand());
    }

    @Test
    void winrmUnavailableIgnoresAnyProbesTheAgentStillSent() {
        DiagnosticResponse r = service.ingest(
                report("WINRM_UNAVAILABLE", "x", Map.of("reachable", false), null, null, null));
        assertNull(r.score());
        assertNull(r.deductions());
    }

    @Test
    void winrmUnavailableWithBlankOrMissingDetailGetsADefaultMessage() {
        DiagnosticResponse blank = service.ingest(report("WINRM_UNAVAILABLE", "   ", null, null, null, null));
        DiagnosticResponse missing = service.ingest(report("WINRM_UNAVAILABLE", null, null, null, null, null));
        assertEquals("Endpoint could not be probed", blank.errorMessage());
        assertEquals("Endpoint could not be probed", missing.errorMessage());
    }

    // ---- ingest: validation ------------------------------------------------------

    @Test
    void okReportWithNoProbesAtAllIsRejectedAndNothingIsSaved() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.ingest(report("OK", null, null, null, null, null)));
        assertTrue(e.getMessage().contains("at least one probe"));
        verify(repository, never()).save(any());
    }

    @Test
    void missingEndpointBlockOrBlankMacIsRejectedBeforeAnythingElseHappens() {
        assertThrows(IllegalArgumentException.class, () -> service.ingest(withMac(null)));
        assertThrows(IllegalArgumentException.class, () -> service.ingest(withMac(new EndpointDto(null, "h", "1.1.1.1"))));
        assertThrows(IllegalArgumentException.class, () -> service.ingest(withMac(new EndpointDto("  ", "h", "1.1.1.1"))));
        verifyNoInteractions(endpointService, repository);
    }

    @Test
    void missingStatusIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.ingest(report(null, null, goodGateway(), null, null, null)));
        assertEquals("status is required", e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void unknownStatusIsRejectedWithAClearMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.ingest(report("BANANA", null, goodGateway(), null, null, null)));
        assertEquals("status must be OK or WINRM_UNAVAILABLE", e.getMessage());
    }

    @Test
    void anAgentCannotSubmitFailedBecauseThatStatusIsReservedForTheWorker() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.ingest(report("FAILED", "x", goodGateway(), null, null, null)));
        assertEquals("status must be OK or WINRM_UNAVAILABLE", e.getMessage());
        verify(repository, never()).save(any());
    }

    // ---- job id parsing ----------------------------------------------------------

    @Test
    void validJobIdIsLinkedAndABadOrBlankOneBecomesNullInsteadOfFailing() {
        UUID jobId = UUID.randomUUID();
        service.ingest(new DiagnosticReportRequest(jobId.toString(), new EndpointDto(MAC, "h", "1.1.1.1"),
                "OK", null, goodGateway(), null, null, null, null));
        service.ingest(new DiagnosticReportRequest("not-a-uuid", new EndpointDto(MAC, "h", "1.1.1.1"),
                "OK", null, goodGateway(), null, null, null, null));
        service.ingest(new DiagnosticReportRequest("  ", new EndpointDto(MAC, "h", "1.1.1.1"),
                "OK", null, goodGateway(), null, null, null, null));

        ArgumentCaptor<EndpointDiagnostic> c = ArgumentCaptor.forClass(EndpointDiagnostic.class);
        verify(repository, org.mockito.Mockito.times(3)).save(c.capture());
        assertEquals(jobId, c.getAllValues().get(0).getJobId());
        assertNull(c.getAllValues().get(1).getJobId());
        assertNull(c.getAllValues().get(2).getJobId());
    }

    // ---- recordFailure -----------------------------------------------------------

    @Test
    void recordFailureWritesAFailedRowWithNullScoreAndTheReason() {
        UUID jobId = UUID.randomUUID();

        DiagnosticResponse r = service.recordFailure(endpointId, jobId, "process timed out");

        assertEquals(EndpointDiagnostic.Status.FAILED, r.status());
        assertNull(r.score());
        assertNull(r.band());
        assertEquals("process timed out", r.errorMessage());

        EndpointDiagnostic row = saved();
        assertEquals(endpointId, row.getEndpointId());
        assertEquals(jobId, row.getJobId());
        assertEquals("process timed out", row.getRawReport().get("error"));
    }

    @Test
    void recordFailureWithNoReasonStillWritesARow() {
        DiagnosticResponse r = service.recordFailure(endpointId, null, null);
        assertEquals("Unknown failure", r.errorMessage());
        assertNull(saved().getJobId());
    }

    // ---- reads -------------------------------------------------------------------

    @Test
    void historyKeepsTheRepositoryOrderNewestFirst() {
        EndpointDiagnostic newer = row(EndpointDiagnostic.Status.OK, 90);
        EndpointDiagnostic older = row(EndpointDiagnostic.Status.WINRM_UNAVAILABLE, null);
        when(repository.findByEndpointIdOrderByCollectedAtDesc(endpointId)).thenReturn(List.of(newer, older));

        List<DiagnosticResponse> out = service.history(endpointId);

        assertEquals(2, out.size());
        assertEquals(newer.getId(), out.get(0).id());
        assertEquals(older.getId(), out.get(1).id());
        assertNull(out.get(1).score());
    }

    @Test
    void latestIsEmptyForAnEndpointThatWasNeverDiagnosed() {
        when(repository.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId)).thenReturn(Optional.empty());
        assertTrue(service.latest(endpointId).isEmpty());
    }

    @Test
    void latestMapsTheNewestRow() {
        EndpointDiagnostic newest = row(EndpointDiagnostic.Status.OK, 77);
        when(repository.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId)).thenReturn(Optional.of(newest));

        DiagnosticResponse r = service.latest(endpointId).orElseThrow();

        assertEquals(newest.getId(), r.id());
        assertEquals(77, r.score());
    }

    private EndpointDiagnostic row(EndpointDiagnostic.Status status, Integer score) {
        return EndpointDiagnostic.builder()
                .id(UUID.randomUUID()).endpointId(endpointId).status(status).score(score)
                .rawReport(Map.of()).collectedAt(Instant.now()).build();
    }
}