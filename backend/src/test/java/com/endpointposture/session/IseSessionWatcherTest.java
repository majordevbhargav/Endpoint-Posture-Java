package com.endpointposture.session;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.job.JobService;
import com.endpointposture.session.IseSessionClient.SessionPoll;
import com.endpointposture.session.dto.IseActiveSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IseSessionWatcherTest {

    static final String MAC = "AA:BB:CC:DD:EE:FF";

    @Mock IseSessionClient client;
    @Mock EndpointRepository endpoints;
    @Mock EndpointService endpointService;
    @Mock JobService jobService;
    IseSessionWatcher watcher;

    @BeforeEach
    void setUp() {
        Endpoint ep = Endpoint.builder().id(java.util.UUID.randomUUID())
                .macAddress(MAC).ipAddress("10.0.0.1").connected(true).build();
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(ep));
        when(endpoints.findByMacAddress(MAC)).thenReturn(Optional.of(ep));
        watcher = new IseSessionWatcher(client, endpoints, endpointService, jobService, new IseLinkHealth(), 2);
    }

    private void pollEmpty() {
        when(client.fetchActiveSessions()).thenReturn(new SessionPoll(true, List.of(), null));
        watcher.tick();
    }

    private void pollPresent() {
        when(client.fetchActiveSessions())
                .thenReturn(new SessionPoll(true, List.of(new IseActiveSession(MAC, "10.0.0.1")), null));
        watcher.tick();
    }

    private void pollFailed() {
        when(client.fetchActiveSessions()).thenReturn(new SessionPoll(false, List.of(), "ISE down"));
        watcher.tick();
    }

    @Test
    void oneMissedPollKeepsEndpointConnected() {
        pollEmpty();
        verify(endpointService, never()).markDisconnected(any());
    }

    @Test
    void twoMissedPollsInARowDisconnects() {
        pollEmpty();
        pollEmpty();
        verify(endpointService, times(1)).markDisconnected(MAC);
    }

    @Test
    void reappearingResetsTheMissCounter() {
        pollEmpty();     // 1 miss
        pollPresent();   // back: counter reset
        pollEmpty();     // 1 miss again, not 2
        verify(endpointService, never()).markDisconnected(any());
    }

    @Test
    void failedPollsDoNotCountAsMisses() {
        pollFailed();
        pollFailed();
        pollFailed();
        verify(endpointService, never()).markDisconnected(any());
        pollEmpty();     // only 1 real miss so far
        verify(endpointService, never()).markDisconnected(any());
    }
}