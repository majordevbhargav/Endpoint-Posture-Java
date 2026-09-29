package com.endpointposture.posture;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.inventory.EndpointInventoryRepository;
import com.endpointposture.posture.dto.AssessmentResponse;
import com.endpointposture.posture.dto.CheckInput;
import com.endpointposture.posture.dto.PostureReportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * overallStatus() is private, so it's exercised the only way it can be:
 * through ingest(), capturing the AssessmentStatus actually passed to
 * AssessmentService.recordAssessment. These tests pin down the rule its
 * Javadoc states and that PostureIngestController's callers rely on:
 * the assessment's overall status can never look better than its worst
 * individual check (relies on AssessmentStatus's declared ordinal order,
 * COMPLIANT < NON_COMPLIANT < ERROR - see AssessmentStatus's own Javadoc
 * warning not to reorder those constants).
 */
@ExtendWith(MockitoExtension.class)
class PostureIngestServiceOverallStatusTest {

    @Mock
    EndpointService endpointService;
    @Mock
    AssessmentService assessmentService;
    @Mock
    EndpointInventoryRepository inventoryRepository;

    private PostureIngestService service;
    private final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PostureIngestService(endpointService, assessmentService, inventoryRepository);

        Endpoint endpoint = Endpoint.builder().id(endpointId).macAddress("AA:BB:CC:DD:EE:FF").build();
        when(endpointService.upsertByMac(any(), any(), any(), any(), any())).thenReturn(endpoint);

        // Echo back whatever status/checks were passed in, so the captor below
        // can inspect exactly what ingest() decided the overall status was.
        when(assessmentService.recordAssessment(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new AssessmentResponse(
                        UUID.randomUUID(), endpointId, null,
                        inv.getArgument(4, AssessmentStatus.class),
                        inv.getArgument(5, String.class),
                        inv.getArgument(2, Instant.class),
                        inv.getArgument(3, Instant.class),
                        inv.getArgument(6, List.class)));
    }

    private PostureReportRequest request(AssessmentStatus reported, List<CheckInput> checks) {
        return new PostureReportRequest(
                null, "AA:BB:CC:DD:EE:FF", null, null, null, null, null,
                Instant.now(), reported, null, checks, null);
    }

    private AssessmentStatus overallStatusFor(PostureReportRequest req) {
        service.ingest(req);
        ArgumentCaptor<AssessmentStatus> captor = ArgumentCaptor.forClass(AssessmentStatus.class);
        verify(assessmentService).recordAssessment(any(), any(), any(), any(), captor.capture(), any(), any());
        return captor.getValue();
    }

    @Test
    void allCompliantChecksKeepTheReportedCompliantStatus() {
        List<CheckInput> checks = List.of(new CheckInput("FIREWALL", AssessmentStatus.COMPLIANT, null));
        assertEquals(AssessmentStatus.COMPLIANT, overallStatusFor(request(AssessmentStatus.COMPLIANT, checks)));
    }

    @Test
    void oneNonCompliantCheckDowngradesAnOtherwiseCompliantReport() {
        List<CheckInput> checks = List.of(
                new CheckInput("FIREWALL", AssessmentStatus.COMPLIANT, null),
                new CheckInput("OPEN_PORTS", AssessmentStatus.NON_COMPLIANT, null));
        assertEquals(AssessmentStatus.NON_COMPLIANT, overallStatusFor(request(AssessmentStatus.COMPLIANT, checks)));
    }

    @Test
    void anErrorCheckOutranksANonCompliantCheck() {
        List<CheckInput> checks = List.of(
                new CheckInput("FIREWALL", AssessmentStatus.NON_COMPLIANT, null),
                new CheckInput("APPLICATIONS", AssessmentStatus.ERROR, null));
        assertEquals(AssessmentStatus.ERROR, overallStatusFor(request(AssessmentStatus.COMPLIANT, checks)));
    }

    @Test
    void reportedStatusWorseThanEveryIndividualCheckIsRespected() {
        // The agent's own top-level status counts too, not just per-check results.
        List<CheckInput> checks = List.of(new CheckInput("FIREWALL", AssessmentStatus.COMPLIANT, null));
        assertEquals(AssessmentStatus.ERROR, overallStatusFor(request(AssessmentStatus.ERROR, checks)));
    }

    @Test
    void noChecksAtAllKeepsTheReportedStatus() {
        assertEquals(AssessmentStatus.COMPLIANT, overallStatusFor(request(AssessmentStatus.COMPLIANT, List.of())));
    }

    @Test
    void multipleNonCompliantChecksStayNonCompliantWithoutAnError() {
        List<CheckInput> checks = List.of(
                new CheckInput("FIREWALL", AssessmentStatus.NON_COMPLIANT, null),
                new CheckInput("APPLICATIONS", AssessmentStatus.NON_COMPLIANT, null));
        assertEquals(AssessmentStatus.NON_COMPLIANT, overallStatusFor(request(AssessmentStatus.COMPLIANT, checks)));
    }
}