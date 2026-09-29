package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.posture.AssessmentRepository;
import com.endpointposture.posture.AssessmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecheckSchedulerTest {

    static final Duration POSTURE = Duration.ofHours(4);
    static final Duration HARDWARE = Duration.ofHours(24);
    static final Duration BACKOFF = Duration.ofHours(6);

    @Mock EndpointRepository endpoints;
    @Mock AssessmentRepository assessments;
    @Mock JobService jobService;

    RecheckScheduler scheduler;
    final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new RecheckScheduler(endpoints, assessments, jobService, 4, 24, 6);
    }

    private Endpoint connected(String ip) {
        return Endpoint.builder().id(id).macAddress("AA:BB:CC:DD:EE:FF").ipAddress(ip).connected(true).build();
    }

    @Test
    void endpointWithNoIpOrHostnameIsSkipped() {
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(connected(null)));
        scheduler.sweep();
        verify(jobService, never()).enqueueIfDue(any(), any(), any(), any());
    }

    @Test
    void postureIsRequestedWithTheConfiguredIntervals() {
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(connected("10.0.0.5")));
        scheduler.sweep();
        verify(jobService).enqueueIfDue(id, JobType.POSTURE_CHECK, POSTURE, BACKOFF);
    }

    @Test
    void hardwareOnlyAfterAtLeastOneNonErrorPosture() {
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(connected("10.0.0.5")));
        when(assessments.existsByEndpointIdAndStatusNot(id, AssessmentStatus.ERROR)).thenReturn(false);
        scheduler.sweep();
        verify(jobService, never()).enqueueIfDue(eq(id), eq(JobType.HARDWARE_CHECK), any(), any());
    }

    @Test
    void hardwareRequestedOnceTheEndpointHasAnsweredPosture() {
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(connected("10.0.0.5")));
        when(assessments.existsByEndpointIdAndStatusNot(id, AssessmentStatus.ERROR)).thenReturn(true);
        scheduler.sweep();
        verify(jobService).enqueueIfDue(id, JobType.HARDWARE_CHECK, HARDWARE, BACKOFF);
    }

    @Test
    void repositoryFailureDoesNotEscapeTheScheduler() {
        when(endpoints.findAllByConnectedTrue()).thenThrow(new RuntimeException("db down"));
        scheduler.sweep(); // must not throw
    }
}