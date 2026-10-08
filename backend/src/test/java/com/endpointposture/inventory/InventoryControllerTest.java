package com.endpointposture.inventory;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.endpoint.FleetListLimitExceededException;
import com.endpointposture.inventory.InventoryController.AppRow;
import com.endpointposture.inventory.InventoryController.PortRow;
import com.endpointposture.policy.PolicyService;
import com.endpointposture.policy.PolicyService.PolicySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryControllerTest {

    @Mock EndpointInventoryRepository inventory;
    @Mock EndpointRepository endpoints;
    @Mock PolicyService policyService;
    InventoryController controller;

    final UUID epId = UUID.randomUUID();
    final Endpoint ep = Endpoint.builder().id(epId).macAddress("AA:BB:CC:DD:EE:FF").hostname("PC1").build();

    @BeforeEach
    void setUp() {
        controller = new InventoryController(inventory, endpoints, policyService, 2000);
        when(endpoints.count()).thenReturn(1L);
        when(endpoints.findAll()).thenReturn(List.of(ep));
        when(policyService.getActive()).thenReturn(new PolicySnapshot(UUID.randomUUID(), "p", 7,
                List.of("Cisco Secure Client"), List.of("uTorrent", "TeamViewer"), "admin", Instant.now()));
    }

    private EndpointInventory inv(UUID endpointId, List<Map<String, Object>> apps, List<Map<String, Object>> ports) {
        return EndpointInventory.builder().endpointId(endpointId).installedApps(apps).listeningPorts(ports).build();
    }

    private Map<String, Object> app(String name) { return Map.of("name", name, "version", "1.0", "publisher", "Pub"); }

    // ---- applications ----
    @Test
    void blockedRequiredAndOrdinaryAppsAreLabelledAgainstThePolicy() {
        when(inventory.findLatestPerEndpoint()).thenReturn(List.of(inv(epId,
                List.of(app("uTorrent 3.5"), app("Cisco Secure Client - AnyConnect"), app("7-Zip")), null)));

        List<AppRow> rows = controller.applications();

        assertEquals(3, rows.size());
        assertEquals("NON_COMPLIANT", rows.get(0).status());
        assertTrue(rows.get(0).summary().contains("v7"));
        assertEquals("COMPLIANT", rows.get(1).status());
        assertNull(rows.get(2).status());
        assertNull(rows.get(2).summary());
        assertEquals("PC1", rows.get(0).hostname());
        assertEquals("AA:BB:CC:DD:EE:FF", rows.get(0).macAddress());
    }

    @Test
    void matchingIsCaseInsensitive() {
        when(inventory.findLatestPerEndpoint()).thenReturn(List.of(inv(epId, List.of(app("TEAMVIEWER host")), null)));
        assertEquals("NON_COMPLIANT", controller.applications().get(0).status());
    }

    @Test
    void blockedWinsWhenAnAppMatchesBothLists() {
        when(policyService.getActive()).thenReturn(new PolicySnapshot(UUID.randomUUID(), "p", 1,
                List.of("Tool"), List.of("Tool Pro"), "a", Instant.now()));
        when(inventory.findLatestPerEndpoint()).thenReturn(List.of(inv(epId, List.of(app("Tool Pro")), null)));
        assertEquals("NON_COMPLIANT", controller.applications().get(0).status());
    }

    @Test
    void blankNamesNullAppListsAndUnknownEndpointsAreSkipped() {
        Map<String, Object> noName = new HashMap<>();
        noName.put("name", null);
        when(inventory.findLatestPerEndpoint()).thenReturn(List.of(
                inv(epId, List.of(noName, Map.of("name", "  "), app("Real App")), null),
                inv(epId, null, null),
                inv(UUID.randomUUID(), List.of(app("Orphan")), null)));

        List<AppRow> rows = controller.applications();

        assertEquals(1, rows.size());
        assertEquals("Real App", rows.get(0).name());
    }

    @Test
    void emptyPolicyListsLabelNothing() {
        when(policyService.getActive()).thenReturn(new PolicySnapshot(UUID.randomUUID(), "p", 2,
                List.of(), List.of(), "a", Instant.now()));
        when(inventory.findLatestPerEndpoint()).thenReturn(List.of(inv(epId, List.of(app("uTorrent")), null)));
        assertNull(controller.applications().get(0).status());
    }

    // ---- ports ----
    @Test
    void reachabilityMapsToCompliantBlockedOrUntested() {
        Map<String, Object> untested = new HashMap<>();
        untested.put("port", 8080);
        when(inventory.findLatestPerEndpoint()).thenReturn(List.of(inv(epId, null, List.of(
                Map.of("port", 443, "process", "svc", "pid", 4, "reachable", true),
                Map.of("port", 3389, "process", "rdp", "pid", 5, "reachable", false),
                untested))));

        List<PortRow> rows = controller.ports();

        assertEquals(3, rows.size());
        assertEquals("COMPLIANT", rows.get(0).status());
        assertEquals(Boolean.TRUE, rows.get(0).reachable());
        assertEquals("BLOCKED", rows.get(1).status());
        assertNull(rows.get(2).status());
        assertNull(rows.get(2).reachable());
        assertNull(rows.get(2).pid());
    }

    @Test
    void portsWithoutANumericPortOrWithoutAnEndpointAreSkipped() {
        when(inventory.findLatestPerEndpoint()).thenReturn(List.of(
                inv(epId, null, List.of(Map.of("port", "443"), Map.of("process", "x"), Map.of("port", 22))),
                inv(UUID.randomUUID(), null, List.of(Map.of("port", 80))),
                inv(epId, null, null)));

        List<PortRow> rows = controller.ports();

        assertEquals(1, rows.size());
        assertEquals(22, rows.get(0).port());
    }

    // ---- fleet cap ----
    @Test
    void applicationsRefuseAboveTheFleetCapAndLoadNothing() {
        when(endpoints.count()).thenReturn(2001L);

        FleetListLimitExceededException e =
                assertThrows(FleetListLimitExceededException.class, () -> controller.applications());

        assertTrue(e.getMessage().contains("2001"));
        assertTrue(e.getMessage().contains("(2000)"));
        verify(inventory, never()).findLatestPerEndpoint();
    }

    @Test
    void portsRefuseAboveTheFleetCapAndLoadNothing() {
        when(endpoints.count()).thenReturn(5000L);

        assertThrows(FleetListLimitExceededException.class, () -> controller.ports());
        verify(inventory, never()).findLatestPerEndpoint();
    }

    @Test
    void exactlyAtTheCapStillWorks() {
        when(endpoints.count()).thenReturn(2000L);
        when(inventory.findLatestPerEndpoint()).thenReturn(List.of());

        assertTrue(controller.applications().isEmpty());
        assertTrue(controller.ports().isEmpty());
    }
}