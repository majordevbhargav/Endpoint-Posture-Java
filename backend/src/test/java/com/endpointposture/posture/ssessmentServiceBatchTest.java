package com.endpointposture.posture;

import com.endpointposture.posture.dto.AssessmentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** S2: many assessments must load their checks in batches, never one query each. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssessmentServiceBatchTest {

    @Mock AssessmentRepository assessments;
    @Mock CheckResultRepository checks;

    AssessmentService service;
    final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AssessmentService(assessments, checks);
        when(checks.findByAssessmentIdIn(anyCollection())).thenReturn(List.of());
    }

    private Assessment assessment() {
        return Assessment.builder().id(UUID.randomUUID()).endpointId(endpointId)
                .status(AssessmentStatus.COMPLIANT).startedAt(Instant.now()).build();
    }

    private CheckResult check(Assessment a, String type) {
        return CheckResult.builder().id(UUID.randomUUID()).assessmentId(a.getId())
                .checkType(type).status(AssessmentStatus.COMPLIANT).createdAt(Instant.now()).build();
    }

    @Test
    void historyLoadsAllChecksWithOneQueryAndAttachesThemToTheRightAssessment() {
        Assessment a1 = assessment(), a2 = assessment(), a3 = assessment();
        when(assessments.findByEndpointIdOrderByCreatedAtDesc(endpointId)).thenReturn(List.of(a1, a2, a3));
        when(checks.findByAssessmentIdIn(anyCollection()))
                .thenReturn(List.of(check(a1, "FIREWALL"), check(a1, "OPEN_PORTS"), check(a3, "FIREWALL")));

        List<AssessmentResponse> out = service.getHistoryForEndpoint(endpointId);

        verify(checks, times(1)).findByAssessmentIdIn(anyCollection());
        verify(checks, never()).findByAssessmentId(any());
        assertEquals(3, out.size());
        assertEquals(2, out.get(0).checks().size());
        assertTrue(out.get(1).checks().isEmpty());
        assertEquals(1, out.get(2).checks().size());
    }

    @Test
    void aLargeFleetIsFetchedInChunksOfAThousand() {
        List<Assessment> many = new ArrayList<>();
        for (int i = 0; i < 2500; i++) many.add(assessment());
        when(assessments.findLatestPerEndpoint()).thenReturn(many);

        List<AssessmentResponse> out = service.getLatestForAllEndpoints();

        assertEquals(2500, out.size());
        verify(checks, times(3)).findByAssessmentIdIn(anyCollection()); // 1000 + 1000 + 500
    }

    @Test
    void noAssessmentsMeansNoCheckQueriesAtAll() {
        when(assessments.findByEndpointIdOrderByCreatedAtDesc(endpointId)).thenReturn(List.of());
        assertTrue(service.getHistoryForEndpoint(endpointId).isEmpty());
        verifyNoInteractions(checks);
    }

    @Test
    void findLatestIsEmptyForAnEndpointThatWasNeverAssessed() {
        when(assessments.findFirstByEndpointIdOrderByCreatedAtDesc(endpointId)).thenReturn(Optional.empty());
        assertTrue(service.findLatestForEndpoint(endpointId).isEmpty());
        assertThrows(AssessmentNotFoundException.class, () -> service.getLatestForEndpoint(endpointId));
    }
}