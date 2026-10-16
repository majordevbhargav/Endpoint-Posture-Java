package com.endpointposture.isefeed;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.isefeed.IseFeedService.Feed;
import com.endpointposture.isefeed.SharingRuleService.Rule;
import com.endpointposture.isefeed.SharingRuleService.Scope;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class IseFeedServiceTest {

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
        r.add("app.ise-feed.password", () -> "feed-test-password");
        r.add("app.jobs.workers.enabled", () -> "false");
        r.add("app.jobs.recheck.enabled", () -> "false");
        r.add("app.retention.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SharingRuleService rules;
    @Autowired IseFeedService feed;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM endpoint");
        jdbc.update("DELETE FROM ise_feed_audit");
        jdbc.update("DELETE FROM ise_sharing_rule WHERE version > 1");
        jdbc.update("UPDATE ise_sharing_rule SET active = true WHERE version = 1");
    }

    /** An endpoint with one assessment whose FIREWALL check has the given status. */
    private void device(String mac, boolean connected, String status, String firewall) {
        UUID ep = jdbc.queryForObject("INSERT INTO endpoint (mac_address, connected) VALUES (?, ?) RETURNING id", UUID.class, mac, connected);
        UUID a = jdbc.queryForObject("INSERT INTO assessment (endpoint_id, status, started_at) VALUES (?, ?, now()) RETURNING id", UUID.class, ep, status);
        jdbc.update("INSERT INTO check_result (assessment_id, check_type, status) VALUES (?, 'FIREWALL', ?)", a, firewall);
        jdbc.update("UPDATE endpoint SET latest_assessment_id = ?, latest_status = ?, latest_assessed_at = now() WHERE id = ?", a, status, ep);
    }

    @Test
    void theSeededRuleIsNoneSoTheFeedStartsEmptyAndIsStillAudited() {
        device("AB:00:00:00:00:01", true, "COMPLIANT", "COMPLIANT");

        Feed f = feed.fetch(0, 100, "ise-feed");

        assertEquals(Scope.NONE.name(), f.scope());
        assertEquals(1, f.ruleVersion());
        assertTrue(f.endpoints().isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ise_feed_audit WHERE caller = 'ise-feed' AND endpoint_count = 0", Long.class));
    }

    @Test
    void connectedScopeSharesConnectedAssessedDevicesWithFailedChecksNamed() {
        device("AB:00:00:00:00:02", true, "NON_COMPLIANT", "NON_COMPLIANT");
        device("AB:00:00:00:00:03", true, "COMPLIANT", "COMPLIANT");
        device("AB:00:00:00:00:04", false, "NON_COMPLIANT", "NON_COMPLIANT"); // disconnected: never shared
        jdbc.update("INSERT INTO endpoint (mac_address, connected) VALUES ('AB:00:00:00:00:05', true)"); // never assessed

        Rule r = rules.replaceActive(Scope.CONNECTED, "admin");
        Feed f = feed.fetch(0, 100, "ise-feed");

        assertEquals(2, r.version());
        assertEquals(2, f.total());
        assertEquals("AB:00:00:00:00:02", f.endpoints().get(0).mac());
        assertEquals("FIREWALL", f.endpoints().get(0).failedChecks());
        assertEquals("none", f.endpoints().get(1).failedChecks());
        assertEquals(2, jdbc.queryForObject("SELECT endpoint_count FROM ise_feed_audit ORDER BY occurred_at DESC LIMIT 1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT rule_version FROM ise_feed_audit ORDER BY occurred_at DESC LIMIT 1", Integer.class));
    }

    @Test
    void nonCompliantScopeExcludesCompliantDevices() {
        device("AB:00:00:00:00:06", true, "NON_COMPLIANT", "NON_COMPLIANT");
        device("AB:00:00:00:00:07", true, "ERROR", "ERROR");
        device("AB:00:00:00:00:08", true, "COMPLIANT", "COMPLIANT");

        rules.replaceActive(Scope.CONNECTED_NON_COMPLIANT, "admin");

        assertEquals(2, feed.fetch(0, 100, "x").total());
    }

    @Test
    void pagingSlicesTheFeed() {
        for (int i = 0; i < 5; i++) device("AB:00:00:00:01:0" + i, true, "COMPLIANT", "COMPLIANT");
        rules.replaceActive(Scope.CONNECTED, "admin");

        assertEquals(5, feed.fetch(0, 2, "x").total());
        assertEquals(2, feed.fetch(0, 2, "x").endpoints().size());
        assertEquals(1, feed.fetch(2, 2, "x").endpoints().size());
    }

    @Test
    void changingTheRuleCreatesOneNewVersionAndReselectingTheSameScopeCreatesNone() {
        Rule v2 = rules.replaceActive(Scope.CONNECTED, "alice");
        Rule same = rules.replaceActive(Scope.CONNECTED, "bob");
        Rule v3 = rules.replaceActive(Scope.NONE, "bob");

        assertEquals(2, v2.version());
        assertEquals("alice", v2.createdBy());
        assertEquals(v2.id(), same.id(), "no empty version for an unchanged scope");
        assertEquals(3, v3.version());
        assertEquals(3, rules.history().size());
        assertEquals(1, rules.history().stream().filter(Rule::active).count());
        assertEquals(Scope.NONE, rules.getActive().scope());
    }
}