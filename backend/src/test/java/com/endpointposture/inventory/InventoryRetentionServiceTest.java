package com.endpointposture.inventory;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class InventoryRetentionServiceTest {

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
        r.add("app.retention.enabled", () -> "false");
    }

    @Autowired EndpointRepository endpoints;
    @Autowired EndpointInventoryRepository inventory;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        inventory.deleteAll();
        endpoints.deleteAll();
    }

    private UUID endpoint(String mac) {
        return endpoints.save(Endpoint.builder().macAddress(mac).build()).getId();
    }

    private void addRuns(UUID endpointId, int count) {
        Instant base = Instant.now();
        for (int i = 0; i < count; i++) {
            inventory.save(EndpointInventory.builder()
                    .endpointId(endpointId)
                    .installedApps(List.of(Map.of("name", "App" + i)))
                    .listeningPorts(List.of(Map.of("port", 80)))
                    .topProcesses(List.of(Map.of("name", "p")))
                    .resourceUsage(Map.of("cpu_percent", 5))
                    .collectedAt(base.minusSeconds(i * 60L)) // i = 0 is newest
                    .build());
        }
    }

    private long count(String where, UUID endpointId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM endpoint_inventory WHERE endpoint_id = ? AND " + where,
                Long.class, endpointId);
    }

    @Test
    void keepsNewestNPayloadsAndNullsTheRestWithoutDeletingRows() {
        UUID a = endpoint("AA:AA:AA:AA:AA:01");
        addRuns(a, 13);

        int pruned = new InventoryRetentionService(jdbc, 10).pruneOldPayloads();

        assertEquals(3, pruned);
        assertEquals(13, count("true", a));                         // rows survive
        assertEquals(10, count("installed_apps IS NOT NULL", a));   // newest 10 keep payloads
        assertEquals(3, count("installed_apps IS NULL AND listening_ports IS NULL "
                + "AND top_processes IS NULL AND resource_usage IS NULL", a));
    }

    @Test
    void isIdempotentAndPerEndpoint() {
        UUID a = endpoint("AA:AA:AA:AA:AA:02");
        UUID b = endpoint("AA:AA:AA:AA:AA:03");
        addRuns(a, 12);
        addRuns(b, 4); // under the limit: must be untouched

        InventoryRetentionService service = new InventoryRetentionService(jdbc, 10);
        assertEquals(2, service.pruneOldPayloads());
        assertEquals(0, service.pruneOldPayloads());
        assertEquals(4, count("installed_apps IS NOT NULL", b));
    }

    @Test
    void latestRowPerEndpointAlwaysKeepsItsPayload() {
        UUID a = endpoint("AA:AA:AA:AA:AA:04");
        addRuns(a, 5);
        new InventoryRetentionService(jdbc, 1).pruneOldPayloads();

        assertEquals(1, inventory.findLatestPerEndpoint().size());
        assertEquals(1, count("installed_apps IS NOT NULL", a));
    }
}