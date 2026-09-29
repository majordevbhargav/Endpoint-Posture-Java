package com.endpointposture.ise;

import com.endpointposture.audit.IseActionAudit;
import com.endpointposture.audit.IseActionAuditRepository;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointNotFoundException;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.posture.AssessmentNotFoundException;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.AssessmentStatus;
import com.endpointposture.posture.dto.AssessmentResponse;
import com.endpointposture.posture.dto.CheckResultResponse;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pins the platform's audit rule: every ISE action writes exactly one audit row, success or failure. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IseActionServiceTest {

    static final String MAC = "AA:BB:CC:DD:EE:FF";

    @Mock EndpointRepository endpoints;
    @Mock AssessmentService assessmentService;
    @Mock IseTransport transport;
    @Mock IseActionAuditRepository audit;

    IseActionService service;
    final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new IseActionService(endpoints, assessmentService, transport, audit);
        when(endpoints.findById(id)).thenReturn(Optional.of(Endpoint.builder().id(id).macAddress(MAC).build()));
        when(audit.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private IseActionAudit savedAudit() {
        ArgumentCaptor<IseActionAudit> c = ArgumentCaptor.forClass(IseActionAudit.class);
        verify(audit).save(c.capture());
        return c.getValue();
    }

    private AssessmentResponse assessment(AssessmentStatus overall, CheckResultResponse... checks) {
        return new AssessmentResponse(UUID.randomUUID(), id, null, overall, "d",
                Instant.now(), Instant.now(), List.of(checks));
    }

    private CheckResultResponse check(String type, AssessmentStatus s) {
        return new CheckResultResponse(UUID.randomUUID(), type, s, null, Instant.now());
    }

    @Test
    void shareSuccessWritesAuditWithOperator() {
        when(assessmentService.getLatestForEndpoint(id)).thenReturn(assessment(AssessmentStatus.COMPLIANT,
                check("FIREWALL", AssessmentStatus.COMPLIANT)));
        when(transport.publishPosture(MAC, "COMPLIANT", "none")).thenReturn(new IseResult(true, "ok"));

        IseResult r = service.sharePosture(id, "alice");

        assertTrue(r.success());
        IseActionAudit a = savedAudit();
        assertEquals("SHARE_POSTURE", a.getActionType());
        assertEquals("alice", a.getOperator());
        assertTrue(a.isSucceeded());
        assertEquals(id, a.getEndpointId());
    }

    @Test
    void shareSendsNamesOfFailedChecks() {
        when(assessmentService.getLatestForEndpoint(id)).thenReturn(assessment(AssessmentStatus.NON_COMPLIANT,
                check("FIREWALL", AssessmentStatus.NON_COMPLIANT),
                check("OPEN_PORTS", AssessmentStatus.COMPLIANT),
                check("APPLICATIONS", AssessmentStatus.ERROR)));
        when(transport.publishPosture(any(), any(), any())).thenReturn(new IseResult(true, "ok"));

        service.sharePosture(id, "alice");

        verify(transport).publishPosture(MAC, "NON_COMPLIANT", "FIREWALL, APPLICATIONS");
    }

    @Test
    void shareFailureFromIseStillWritesAuditRow() {
        when(assessmentService.getLatestForEndpoint(id)).thenReturn(assessment(AssessmentStatus.COMPLIANT));
        when(transport.publishPosture(any(), any(), any())).thenReturn(new IseResult(false, "ISE down"));

        IseResult r = service.sharePosture(id, "alice");

        assertFalse(r.success());
        IseActionAudit a = savedAudit();
        assertFalse(a.isSucceeded());
        assertEquals("ISE down", a.getDetail());
    }

    @Test
    void shareWithNoStoredAssessmentIsAFailedResultAndStillAudited() {
        when(assessmentService.getLatestForEndpoint(id)).thenThrow(new AssessmentNotFoundException(id.toString()));

        IseResult r = service.sharePosture(id, "alice");

        assertFalse(r.success());
        verify(transport, never()).publishPosture(any(), any(), any());
        assertFalse(savedAudit().isSucceeded());
    }

    @Test
    void restrictAndClearUseTheirOwnAuditTypes() {
        when(transport.publishEnforcement(eq(MAC), eq(EnforcementAction.RESTRICT), any())).thenReturn(new IseResult(true, "x"));
        service.restrict(id, null, "bob");
        assertEquals("RESTRICT", savedAudit().getActionType());
    }

    @Test
    void clearFailureIsAuditedAsClearRestriction() {
        when(transport.publishEnforcement(eq(MAC), eq(EnforcementAction.CLEAR), any())).thenReturn(new IseResult(false, "boom"));
        IseResult r = service.clearRestriction(id, "bob");
        assertFalse(r.success());
        IseActionAudit a = savedAudit();
        assertEquals("CLEAR_RESTRICTION", a.getActionType());
        assertFalse(a.isSucceeded());
    }

    @Test
    void unknownEndpointThrowsAndCallsNothing() {
        UUID other = UUID.randomUUID();
        when(endpoints.findById(other)).thenReturn(Optional.empty());
        assertThrows(EndpointNotFoundException.class, () -> service.restrict(other, null, "bob"));
        verify(transport, never()).publishEnforcement(any(), any(), any());
        verify(audit, never()).save(any());
    }
}