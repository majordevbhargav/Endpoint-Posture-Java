package com.endpointposture.hardware;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.hardware.dto.HardwareReportRequest;
import com.endpointposture.hardware.dto.HardwareReportRequest.EndpointDto;
import com.endpointposture.hardware.scoring.BatteryScorer;
import com.endpointposture.hardware.scoring.CpuScorer;
import com.endpointposture.hardware.scoring.MemoryScorer;
import com.endpointposture.hardware.scoring.StorageScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Uses the real scorers; only persistence is mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HardwareIngestServiceTest {

    @Mock EndpointService endpointService;
    @Mock HardwareHealthService healthService;

    HardwareIngestService service;
    final AtomicReference<Object[]> captured = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new HardwareIngestService(endpointService, healthService,
                new CpuScorer(), new MemoryScorer(), new StorageScorer(), new BatteryScorer());
        when(endpointService.upsertByMac(any(), any(), any(), any(), any()))
                .thenReturn(Endpoint.builder().id(UUID.randomUUID()).macAddress("AA:BB:CC:DD:EE:FF").build());
        when(healthService.recordReport(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> { captured.set(inv.getArguments()); return null; });
    }

    // recordReport argument positions: 6 cpu, 7 memory, 8 storage, 9 battery
    private int score(int index) { return (Integer) captured.get()[index]; }

    private EndpointDto ep(String mac) {
        return new EndpointDto("Dell", "Latitude", "SN1", "1.0", mac, "host", "10.0.0.5");
    }

    private Map<String, Object> cpuMem() {
        return Map.of("cpu", Map.of("LoadPercentage", 20), "memory", Map.of("UsedPercent", 40.0));
    }

    private HardwareReportRequest req(EndpointDto e, Map<String, Object> cpuMem,
                                      Map<String, Object> storage, Map<String, Object> battery) {
        return new HardwareReportRequest(null, e, cpuMem, storage, battery, null, null, null);
    }

    @Test
    void singleDiskSentAsBareObjectIsHandled() {
        // PowerShell ConvertTo-Json collapses a one-element array to a bare object.
        Map<String, Object> storage = Map.of("physical_disks", Map.of("HealthStatus", "Healthy"));
        service.ingest(req(ep("AA:BB:CC:DD:EE:FF"), cpuMem(), storage, null));
        assertEquals(100, score(8));
        assertEquals(80, score(6));
        assertEquals(60, score(7));
    }

    @Test
    void diskListAndBatteryListAreScored() {
        Map<String, Object> storage = Map.of("physical_disks",
                List.of(Map.of("HealthStatus", "Healthy"), Map.of("HealthStatus", "Warning")));
        Map<String, Object> battery = Map.of("battery_static",
                Map.of("DesignedCapacity", 50000, "FullChargedCapacity", 40000)); // bare object again
        service.ingest(req(ep("AA:BB:CC:DD:EE:FF"), cpuMem(), storage, battery));
        assertEquals(50, score(8));
        assertEquals(80, score(9));
    }

    @Test
    void desktopWithNoBatteryGetsNullNotZero() {
        Map<String, Object> storage = Map.of("physical_disks", List.of(Map.of("HealthStatus", "Healthy")));
        service.ingest(req(ep("AA:BB:CC:DD:EE:FF"), cpuMem(), storage, Map.of()));
        assertNull(captured.get()[9]);
    }

    @Test
    void missingMacIsRejectedBeforeAnythingIsSaved() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(req(ep(" "), cpuMem(), Map.of(), null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(req(null, cpuMem(), Map.of(), null)));
        verify(endpointService, never()).upsertByMac(any(), any(), any(), any(), any());
    }

    @Test
    void missingRequiredScoresAreRejected() {
        Map<String, Object> noDisks = Map.of();
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(req(ep("AA:BB:CC:DD:EE:FF"), cpuMem(), noDisks, null)));
        verify(healthService, never()).recordReport(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any());
    }
}