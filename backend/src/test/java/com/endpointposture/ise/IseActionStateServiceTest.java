package com.endpointposture.ise;

import com.endpointposture.audit.IseActionAudit;
import com.endpointposture.audit.IseActionAuditRepository;
import com.endpointposture.ise.dto.IseActionStateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IseActionStateServiceTest {

    @Mock
    private IseActionAuditRepository auditRepository;

    private IseActionStateService service;

    @BeforeEach
    void setUp() {
        service = new IseActionStateService(auditRepository);
    }

    @Test
    void getLatestEnforcementStatesMapsAuditEntitiesToDto() {
        UUID ep1 = UUID.randomUUID();
        UUID ep2 = UUID.randomUUID();
        Instant now = Instant.now();

        IseActionAudit audit1 = IseActionAudit.builder()
                .id(UUID.randomUUID())
                .endpointId(ep1)
                .actionType("RESTRICT")
                .succeeded(true)
                .operator("admin")
                .detail("Quarantined")
                .occurredAt(now.minusSeconds(60))
                .build();

        IseActionAudit audit2 = IseActionAudit.builder()
                .id(UUID.randomUUID())
                .endpointId(ep2)
                .actionType("CLEAR_RESTRICTION")
                .succeeded(false)
                .operator("operator1")
                .detail("ISE connection timed out")
                .occurredAt(now.minusSeconds(10))
                .build();

        when(auditRepository.findLatestEnforcementActionsPerEndpoint()).thenReturn(List.of(audit1, audit2));

        List<IseActionStateResponse> result = service.getLatestEnforcementStates();

        assertEquals(2, result.size());

        IseActionStateResponse r1 = result.get(0);
        assertEquals(ep1, r1.endpointId());
        assertEquals("RESTRICT", r1.actionType());
        assertTrue(r1.succeeded());
        assertEquals("admin", r1.operator());
        assertEquals("Quarantined", r1.detail());
        assertEquals(now.minusSeconds(60), r1.occurredAt());

        IseActionStateResponse r2 = result.get(1);
        assertEquals(ep2, r2.endpointId());
        assertEquals("CLEAR_RESTRICTION", r2.actionType());
        assertFalse(r2.succeeded());
        assertEquals("operator1", r2.operator());
        assertEquals("ISE connection timed out", r2.detail());
        assertEquals(now.minusSeconds(10), r2.occurredAt());

        verify(auditRepository).findLatestEnforcementActionsPerEndpoint();
    }

    @Test
    void getLatestEnforcementStatesReturnsEmptyListWhenNoRecordsExist() {
        when(auditRepository.findLatestEnforcementActionsPerEndpoint()).thenReturn(List.of());

        List<IseActionStateResponse> result = service.getLatestEnforcementStates();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(auditRepository).findLatestEnforcementActionsPerEndpoint();
    }
}
