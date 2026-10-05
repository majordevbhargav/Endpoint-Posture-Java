package com.endpointposture.session;

import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.endpoint.EndpointRepository.ConnectedRow;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IseSessionWatcherTest {

    static final String MAC = "AA:BB:CC:DD:EE:FF";

    @Mock IseSessionClient client;
    @Mock EndpointRepository endpoints;
    @Mock SessionBatchWriter batch;
    IseSessionWatcher watcher;

    private static ConnectedRow row(String mac, String ip) {
        return new ConnectedRow() {
            public String getMacAddress() { return mac; }
            public String getIpAddress() { return ip; }
        };
    }

    @BeforeEach
    void setUp() {
        when(endpoints.findConnectedRows()).thenReturn(List.of(row(MAC, "10.0.0.1")));
        watcher = new IseSessionWatcher(client, endpoints, batch, new IseLinkHealth(), 2);
    }

    private void poll(IseActiveSession... sessions) {
        when(client.fetchActiveSessions()).thenReturn(new SessionPoll(true, List.of(sessions), null));
        watcher.tick();
    }

    private void pollFailed() {
        when(client.fetchActiveSessions()).thenReturn(new SessionPoll(false, List.of(), "ISE down"));
        watcher.tick();
    }

    @Test
    void oneMissedPollKeepsEndpointConnected() {
        poll();
        verify(batch, never()).markDisconnected(any());
    }

    @Test
    void twoMissedPollsInARowDisconnects() {
        poll();
        poll();
        verify(batch, times(1)).markDisconnected(List.of(MAC));
    }

    @Test
    void reappearingResetsTheMissCounter() {
        poll();                                          // 1 miss
        poll(new IseActiveSession(MAC, "10.0.0.1"));     // back: counter reset
        poll();                                          // 1 miss again, not 2
        verify(batch, never()).markDisconnected(any());
    }

    @Test
    void failedPollsDoNotCountAsMisses() {
        pollFailed();
        pollFailed();
        pollFailed();
        verify(batch, never()).markDisconnected(any());
        poll();                                          // only 1 real miss so far
        verify(batch, never()).markDisconnected(any());
    }

    @Test
    void aFailedPollChangesNothingInTheDatabase() {
        pollFailed();
        verifyNoInteractions(batch);
        verify(endpoints, never()).findConnectedRows();
    }

    @Test
    void aNewSessionIsConnectedAndGetsARecheck() {
        String newMac = "11:22:33:44:55:66";
        poll(new IseActiveSession(MAC, "10.0.0.1"), new IseActiveSession("11-22-33-44-55-66", "10.0.0.2"));

        verify(batch).markConnected(Map.of(newMac, "10.0.0.2"));
        verify(batch).enqueueReconnectChecks(List.of(newMac));
    }

    @Test
    void aKnownConnectedDeviceIsOnlyTouchedNotReconnected() {
        poll(new IseActiveSession(MAC, "10.0.0.1"));

        verify(batch, never()).markConnected(any());
        verify(batch, never()).enqueueReconnectChecks(any());
        verify(batch).touchSeen(List.of(MAC));
    }

    @Test
    void aChangedIpIsUpdatedWithoutAReconnect() {
        poll(new IseActiveSession(MAC, "10.0.0.99"));

        verify(batch).updateIps(Map.of(MAC, "10.0.0.99"));
        verify(batch, never()).markConnected(any());
    }

    @Test
    void aSessionWithNoIpDoesNotWipeTheStoredIp() {
        poll(new IseActiveSession(MAC, null));
        verify(batch).updateIps(Map.of());
    }

    @Test
    void duplicateSessionsForOneMacCollapseToOne() {
        String newMac = "11:22:33:44:55:66";
        poll(new IseActiveSession(newMac, null), new IseActiveSession(newMac.toLowerCase(), "10.0.0.7"),
                new IseActiveSession(MAC, "10.0.0.1"));

        verify(batch).markConnected(Map.of(newMac, "10.0.0.7"));
    }
}