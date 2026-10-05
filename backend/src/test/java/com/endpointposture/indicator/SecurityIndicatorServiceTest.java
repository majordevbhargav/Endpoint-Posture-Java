package com.endpointposture.indicator;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.indicator.EndpointSecurityIndicator.RiskLevel;
import com.endpointposture.indicator.EndpointSecurityIndicator.Status;
import com.endpointposture.indicator.dto.SecurityIndicatorResponse;
import com.endpointposture.indicator.dto.SecurityReportRequest;
import com.endpointposture.indicator.dto.SecurityReportRequest.Connection;
import com.endpointposture.indicator.dto.SecurityReportRequest.EndpointDto;
import com.endpointposture.indicator.dto.SecurityReportRequest.Sample;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecurityIndicatorServiceTest {

    static final String MAC = "AA:BB:CC:DD:EE:FF";

    @Mock EndpointSecurityIndicatorRepository repository;
    @Mock EndpointService endpointService;

    SecurityIndicatorService service;      // real analyzer
    final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SecurityIndicatorService(repository, endpointService,
                new SecurityIndicatorAnalyzer(), new ObjectMapper());
        when(endpointService.upsertByMac(any(), any(), any(), any(), any()))
                .thenReturn(Endpoint.builder().id(endpointId).macAddress(MAC).build());
        when(repository.save(any())).thenAnswer(i -> {
            EndpointSecurityIndicator e = i.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCollectedAt(Instant.now());
            return e;
        });
    }

    // ---- helpers ----
    private Connection c(String addr, int rport, int lport) { return new Connection(addr, rport, lport, 1, "proc"); }
    private Sample s(int i, Connection... cs) { return new Sample(i, i * 5.0, List.of(cs)); }

    private SecurityReportRequest req(String status, String detail, Integer interval, List<Sample> samples) {
        return new SecurityReportRequest(null, new EndpointDto(MAC, "host", "10.0.0.5"),
                status, detail, 55, interval, samples);
    }

    private EndpointSecurityIndicator saved() {
        ArgumentCaptor<EndpointSecurityIndicator> c = ArgumentCaptor.forClass(EndpointSecurityIndicator.class);
        verify(repository).save(c.capture());
        return c.getValue();
    }

    // ---- validation ----
    @Test
    void missingEndpointOrBlankMacIsRejectedBeforeAnythingHappens() {
        for (EndpointDto ep : new EndpointDto[]{null, new EndpointDto(null, "h", "1.1.1.1"), new EndpointDto("  ", "h", "1.1.1.1")}) {
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                    new SecurityReportRequest(null, ep, "OK", null, 55, 5, List.of(s(0)))));
        }
        verifyNoInteractions(endpointService, repository);
    }

    @Test
    void nullStatusIsRejected() {
        var e = assertThrows(IllegalArgumentException.class, () -> service.ingest(req(null, null, 5, List.of(s(0)))));
        assertEquals("status is required", e.getMessage());
    }

    @Test
    void unknownAndFailedStatusesAreRejectedBecauseFailedIsReservedForTheWorker() {
        for (String bad : List.of("BANANA", "FAILED", "failed")) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> service.ingest(req(bad, null, 5, List.of(s(0)))));
            assertEquals("status must be OK or WINRM_UNAVAILABLE", e.getMessage());
        }
        verify(repository, never()).save(any());
    }

    @Test
    void okReportWithNoUsableSamplesIsRejected() {
        List<Sample> onlyNulls = new ArrayList<>();
        onlyNulls.add(null);
        for (List<Sample> samples : new List[]{null, List.of(), onlyNulls}) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> service.ingest(req("OK", null, 5, samples)));
            assertTrue(e.getMessage().contains("at least one sample"));
        }
        verify(repository, never()).save(any());
    }

    @Test
    void statusIsCaseInsensitiveAndTrimmed() {
        assertEquals(Status.OK, service.ingest(req("  ok ", null, 5, List.of(s(0)))).status());
    }

    // ---- OK path (real analyzer) ----
    @Test
    void quietHostIsStoredWithRiskNoneAndNoFindings() {
        SecurityIndicatorResponse r = service.ingest(req("OK", null, 5, List.of(s(0, c("8.8.8.8", 443, 50000)))));

        assertEquals(Status.OK, r.status());
        assertEquals(RiskLevel.NONE, r.riskLevel());
        assertTrue(r.findings().isEmpty());
        assertNull(r.errorMessage());
        assertEquals(endpointId, saved().getEndpointId());
    }

    @Test
    void suspectExternalPortProducesAMediumFinding() {
        SecurityIndicatorResponse r = service.ingest(
                req("OK", null, 5, List.of(s(0, c("203.0.113.9", 4444, 50000)))));
        assertEquals(RiskLevel.MEDIUM, r.riskLevel());
        assertEquals("SUSPECT_PORT", r.findings().get(0).get("type"));
    }

    @Test
    void rawReportIsStoredAsAMapOfTheWholeRequest() {
        service.ingest(req("OK", null, 5, List.of(s(0, c("8.8.8.8", 443, 50000)))));
        Map<String, Object> raw = saved().getRawReport();
        assertEquals("OK", raw.get("status"));
        assertNotNull(raw.get("samples"));
        assertNotNull(raw.get("endpoint"));
    }

    @Test
    void reportIsAttachedByMacWithIpAndHostname() {
        service.ingest(req("OK", null, 5, List.of(s(0))));
        verify(endpointService).upsertByMac(MAC, "10.0.0.5", "host", null, null);
    }

    // ---- analyzer interaction (mock) ----
    @Test
    void nullIntervalDefaultsToFiveAndNullSamplesAreFilteredOut() {
        SecurityIndicatorAnalyzer analyzer = mock(SecurityIndicatorAnalyzer.class);
        when(analyzer.analyze(anyList(), anyInt()))
                .thenReturn(new SecurityIndicatorAnalyzer.Result(RiskLevel.NONE, List.of(), Map.of()));
        SecurityIndicatorService svc = new SecurityIndicatorService(repository, endpointService, analyzer, new ObjectMapper());

        List<Sample> withNull = new ArrayList<>();
        withNull.add(s(0));
        withNull.add(null);
        withNull.add(s(1));
        svc.ingest(req("OK", null, null, withNull));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Sample>> captor = ArgumentCaptor.forClass(List.class);
        verify(analyzer).analyze(captor.capture(), eq(5));
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void explicitIntervalIsPassedThrough() {
        SecurityIndicatorAnalyzer analyzer = mock(SecurityIndicatorAnalyzer.class);
        when(analyzer.analyze(anyList(), anyInt()))
                .thenReturn(new SecurityIndicatorAnalyzer.Result(RiskLevel.NONE, List.of(), Map.of()));
        new SecurityIndicatorService(repository, endpointService, analyzer, new ObjectMapper())
                .ingest(req("OK", null, 10, List.of(s(0))));
        verify(analyzer).analyze(anyList(), eq(10));
    }

    // ---- WINRM_UNAVAILABLE ----
    @Test
    void winrmUnavailableStoresNullRiskFindingsAndSummaryNeverNone() {
        SecurityIndicatorResponse r = service.ingest(req("WINRM_UNAVAILABLE", "WinRM refused", 5, null));

        assertEquals(Status.WINRM_UNAVAILABLE, r.status());
        assertNull(r.riskLevel());
        assertNull(r.findings());
        assertNull(r.summary());
        assertEquals("WinRM refused", r.errorMessage());
    }

    @Test
    void winrmUnavailableNeverRunsTheAnalyzerEvenIfSamplesArrive() {
        SecurityIndicatorAnalyzer analyzer = mock(SecurityIndicatorAnalyzer.class);
        new SecurityIndicatorService(repository, endpointService, analyzer, new ObjectMapper())
                .ingest(req("WINRM_UNAVAILABLE", "x", 5, List.of(s(0, c("203.0.113.9", 4444, 1)))));
        verifyNoInteractions(analyzer);
    }

    @Test
    void blankOrMissingDetailGetsADefaultMessage() {
        assertEquals("Endpoint could not be sampled", service.ingest(req("WINRM_UNAVAILABLE", "  ", 5, null)).errorMessage());
        assertEquals("Endpoint could not be sampled", service.ingest(req("WINRM_UNAVAILABLE", null, 5, null)).errorMessage());
    }

    // ---- jobId ----
    @Test
    void goodJobIdIsLinkedBadOrBlankBecomesNull() {
        UUID jobId = UUID.randomUUID();
        for (String j : new String[]{jobId.toString(), "nope", " "}) {
            service.ingest(new SecurityReportRequest(j, new EndpointDto(MAC, "h", "1.1.1.1"),
                    "OK", null, 55, 5, List.of(s(0))));
        }
        ArgumentCaptor<EndpointSecurityIndicator> c = ArgumentCaptor.forClass(EndpointSecurityIndicator.class);
        verify(repository, org.mockito.Mockito.times(3)).save(c.capture());
        assertEquals(jobId, c.getAllValues().get(0).getJobId());
        assertNull(c.getAllValues().get(1).getJobId());
        assertNull(c.getAllValues().get(2).getJobId());
    }

    // ---- recordFailure & reads ----
    @Test
    void recordFailureWritesFailedRowWithNullRisk() {
        UUID jobId = UUID.randomUUID();
        SecurityIndicatorResponse r = service.recordFailure(endpointId, jobId, "timed out");

        assertEquals(Status.FAILED, r.status());
        assertNull(r.riskLevel());
        assertEquals("timed out", r.errorMessage());
        EndpointSecurityIndicator row = saved();
        assertEquals(jobId, row.getJobId());
        assertEquals("timed out", row.getRawReport().get("error"));
    }

    @Test
    void recordFailureWithNoReasonStillWritesARow() {
        assertEquals("Unknown failure", service.recordFailure(endpointId, null, null).errorMessage());
    }

    @Test
    void historyKeepsRepositoryOrderAndLatestIsEmptyWhenNeverScanned() {
        EndpointSecurityIndicator a = row(RiskLevel.HIGH);
        EndpointSecurityIndicator b = row(RiskLevel.NONE);
        when(repository.findByEndpointIdOrderByCollectedAtDesc(endpointId)).thenReturn(List.of(a, b));
        List<SecurityIndicatorResponse> out = service.history(endpointId);
        assertEquals(a.getId(), out.get(0).id());
        assertEquals(b.getId(), out.get(1).id());

        when(repository.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId)).thenReturn(Optional.empty());
        assertTrue(service.latest(endpointId).isEmpty());
    }

    private EndpointSecurityIndicator row(RiskLevel risk) {
        return EndpointSecurityIndicator.builder().id(UUID.randomUUID()).endpointId(endpointId)
                .status(Status.OK).riskLevel(risk).rawReport(Map.of()).collectedAt(Instant.now()).build();
    }
}