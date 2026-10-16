package com.endpointposture.inventory;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.endpoint.dto.PageResponse;
import com.endpointposture.inventory.InventoryController.AppRow;
import com.endpointposture.inventory.InventoryController.PortRow;
import com.endpointposture.policy.PolicyService;
import com.endpointposture.policy.PolicyService.PolicySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class InventoryQueryServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("endpoint_posture_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("app.jwt.secret", () -> "testcontainers-only-secret-at-least-256-bits-long-value");
        r.add("app.seed-admin.password", () -> "test-admin-password");
        r.add("app.posture.api-key", () -> "test-agent-key");
        r.add("app.jobs.workers.enabled", () -> "false");
        r.add("app.jobs.recheck.enabled", () -> "false");
        r.add("app.retention.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EndpointInventoryRepository inventoryRepo;

    InventoryQueryService service;

    @BeforeEach
    void setUp() {
        PolicyService policy = Mockito.mock(PolicyService.class);
        when(policy.getActive()).thenReturn(new PolicySnapshot(UUID.randomUUID(), "p", 7,
                List.of("Cisco Secure Client"), List.of("uTorrent", "100%_Bad"), "admin", Instant.now()));
        service = new InventoryQueryService(jdbc, policy);

        UUID a = endpoint("FF:00:00:00:00:01", "HOST-A");
        inventory(a, """
                [{"name":"uTorrent 3.5","version":"3.5","publisher":"BitTorrent"},
                 {"name":"Cisco Secure Client - AnyConnect","version":"5"},
                 {"name":"7-Zip","version":"23"},
                 {"name":"  "},{"x":1},"junk"]""", """
                [{"port":443,"process":"svc","pid":4,"reachable":true},
                 {"port":3389,"process":"rdp","pid":5,"reachable":false},
                 {"port":8080},{"process":"noport"}]""");
        UUID b = endpoint("FF:00:00:00:00:02", "HOST-B");
        jdbc.update("INSERT INTO endpoint_inventory (endpoint_id, installed_apps, listening_ports) VALUES (?, '\"scalar\"', NULL)", b);
        jdbc.update("UPDATE endpoint SET latest_inventory_id = (SELECT id FROM endpoint_inventory WHERE endpoint_id = ?) WHERE id = ?", b, b);
        endpoint("FF:00:00:00:00:03", "NO-INVENTORY");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM endpoint");
    }

    private UUID endpoint(String mac, String host) {
        return jdbc.queryForObject("INSERT INTO endpoint (mac_address, hostname) VALUES (?, ?) RETURNING id", UUID.class, mac, host);
    }

    private void inventory(UUID endpointId, String apps, String ports) {
        UUID inv = jdbc.queryForObject("""
                INSERT INTO endpoint_inventory (endpoint_id, installed_apps, listening_ports)
                VALUES (?, ?::jsonb, ?::jsonb) RETURNING id""", UUID.class, endpointId, apps, ports);
        jdbc.update("UPDATE endpoint SET latest_inventory_id = ? WHERE id = ?", inv, endpointId);
    }

    @Test
    void appsAreLabelledAgainstThePolicyAndMalformedElementsAreSkipped() {
        PageResponse<AppRow> r = service.applications(0, 25, null, null);

        assertEquals(3, r.total());
        assertEquals("7-Zip", r.items().get(0).name());
        assertNull(r.items().get(0).status());
        assertEquals("COMPLIANT", r.items().get(1).status());
        assertEquals("NON_COMPLIANT", r.items().get(2).status());
        assertTrue(r.items().get(2).summary().contains("v7"));
    }

    @Test
    void statusSearchAndPagingFiltersWork() {
        assertEquals(1, service.applications(0, 25, null, "NON_COMPLIANT").total());
        assertEquals(1, service.applications(0, 25, null, "COMPLIANT").total());
        assertEquals(1, service.applications(0, 25, "zip", null).total());
        assertEquals(3, service.applications(0, 25, "host-a", null).total());
        assertEquals(1, service.applications(2, 1, null, null).items().size());
        assertEquals(3, service.applications(0, 1, null, null).total());
    }

    @Test
    void likeWildcardsInSearchAndInPolicyPatternsAreLiteral() {
        assertEquals(0, service.applications(0, 25, "%", null).total(), "a literal % must not match everything");
        assertEquals(0, service.applications(0, 25, "_", null).total());
        // policy pattern "100%_Bad" must not act as a wildcard that blocks unrelated apps
        assertEquals(1, service.applications(0, 25, null, "NON_COMPLIANT").total());
    }

    @Test
    void portsExpandWithReachabilityAndSkipEntriesWithoutANumericPort() {
        PageResponse<PortRow> r = service.ports(0, 25, null, null);

        assertEquals(3, r.total());
        assertEquals(443, r.items().get(0).port());
        assertEquals("COMPLIANT", r.items().get(0).status());
        assertEquals("BLOCKED", r.items().get(1).status());
        assertNull(r.items().get(2).reachable());
        assertEquals(1, service.ports(0, 25, null, "BLOCKED").total());
        assertEquals(1, service.ports(0, 25, null, "REACHABLE").total());
        assertEquals(1, service.ports(0, 25, "3389", null).total());
    }

    @Test
    void summariesCountTheFleet() {
        InventoryQueryService.AppSummary a = service.applicationsSummary();
        assertEquals(2, a.evaluatedEndpoints());
        assertEquals(6, a.totalApps());          // array length of HOST-A's raw array (junk included)
        assertEquals(1, a.compliant());
        assertEquals(1, a.nonCompliant());

        InventoryQueryService.PortSummary p = service.portsSummary();
        assertEquals(3, p.totalPorts());
        assertEquals(1, p.reachable());
        assertEquals(1, p.blocked());
    }

    @Test
    void ingestMovesThePointerForwardOnly() {
        UUID ep = jdbc.queryForObject("SELECT id FROM endpoint WHERE mac_address = 'FF:00:00:00:00:03'", UUID.class);
        UUID newer = jdbc.queryForObject("INSERT INTO endpoint_inventory (endpoint_id, installed_apps) VALUES (?, '[]') RETURNING id", UUID.class, ep);
        UUID older = jdbc.queryForObject("INSERT INTO endpoint_inventory (endpoint_id, installed_apps, collected_at) VALUES (?, '[]', now() - interval '1 hour') RETURNING id", UUID.class, ep);

        inventoryRepo.updateEndpointLatest(ep, newer, Instant.now());
        inventoryRepo.updateEndpointLatest(ep, older, Instant.now().minusSeconds(3600)); // late, older write must lose

        assertEquals(newer, jdbc.queryForObject("SELECT latest_inventory_id FROM endpoint WHERE id = ?", UUID.class, ep));
    }
}