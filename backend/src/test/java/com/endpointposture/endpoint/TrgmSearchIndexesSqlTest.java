package com.endpointposture.endpoint;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.endpoint.dto.EndpointListItem;
import com.endpointposture.endpoint.dto.PageResponse;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class TrgmSearchIndexesSqlTest {

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
    @Autowired EndpointQueryService query;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM hardware_health");
        jdbc.update("DELETE FROM endpoint");
    }

    @Test
    void pgTrgmExtensionAndGinIndexesExist() {
        Integer extCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
        assertEquals(1, extCount, "pg_trgm extension must be installed");

        List<String> indexes = jdbc.queryForList(
                """
                SELECT indexname FROM pg_indexes
                 WHERE indexname IN (
                    'idx_endpoint_hostname_trgm',
                    'idx_endpoint_mac_trgm',
                    'idx_endpoint_ip_trgm',
                    'idx_endpoint_os_trgm',
                    'idx_hardware_health_model_trgm'
                 )
                """, String.class);
        assertEquals(5, indexes.size(), "All 5 trigram GIN indexes must exist");
        assertTrue(indexes.contains("idx_endpoint_hostname_trgm"));
        assertTrue(indexes.contains("idx_endpoint_mac_trgm"));
        assertTrue(indexes.contains("idx_endpoint_ip_trgm"));
        assertTrue(indexes.contains("idx_endpoint_os_trgm"));
        assertTrue(indexes.contains("idx_hardware_health_model_trgm"));
    }

    @Test
    void endpointQueryServicePageWithQueryWorksWithTrigramIndexes() {
        jdbc.update("""
                INSERT INTO endpoint (id, mac_address, hostname, ip_address, os_name, connected)
                VALUES (gen_random_uuid(), '00:11:22:33:44:55', 'finance-laptop-01', '192.168.1.50', 'Microsoft Windows 11 Enterprise', true),
                       (gen_random_uuid(), '66:77:88:99:AA:BB', 'marketing-desktop-02', '192.168.1.51', 'Microsoft Windows 10 Pro', true)
                """);

        PageResponse<EndpointListItem> resHost = query.page(0, 25, "finance", null, null);
        assertEquals(1, resHost.total());
        assertEquals("finance-laptop-01", resHost.items().get(0).hostname());

        PageResponse<EndpointListItem> resMac = query.page(0, 25, "22:33", null, null);
        assertEquals(1, resMac.total());
        assertEquals("00:11:22:33:44:55", resMac.items().get(0).macAddress());

        PageResponse<EndpointListItem> resIp = query.page(0, 25, "1.51", null, null);
        assertEquals(1, resIp.total());
        assertEquals("marketing-desktop-02", resIp.items().get(0).hostname());

        PageResponse<EndpointListItem> resOs = query.page(0, 25, "Enterprise", null, null);
        assertEquals(1, resOs.total());
        assertEquals("finance-laptop-01", resOs.items().get(0).hostname());

        PageResponse<EndpointListItem> resNone = query.page(0, 25, "nonexistent", null, null);
        assertEquals(0, resNone.total());
    }
}
