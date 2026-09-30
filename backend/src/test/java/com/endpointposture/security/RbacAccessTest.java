package com.endpointposture.security;

import com.endpointposture.EndpointPostureApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls the real API as each role. Status meaning in these tests:
 * 403 = blocked by RBAC; 404 = RBAC let the call through and the service
 * answered "no such endpoint" (the id below never exists), so nothing is
 * ever sent to ISE.
 */
@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
@AutoConfigureMockMvc
class RbacAccessTest {

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

    @Autowired
    MockMvc mvc;

    private static final String UNKNOWN = "00000000-0000-0000-0000-000000000001";
    private static final String BODY = "{\"endpointId\":\"" + UNKNOWN + "\"}";

    private int post(String path, String role) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                .with(SecurityMockMvcRequestPostProcessors.user("u").roles(role))
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse().getStatus();
    }

    private int get(String path, String role) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path)
                .with(SecurityMockMvcRequestPostProcessors.user("u").roles(role)))
                .andReturn().getResponse().getStatus();
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v1/ise/enforcement/restrict, VIEWER,   403",
            "/api/v1/ise/enforcement/restrict, ANALYST,  403",
            "/api/v1/ise/enforcement/restrict, OPERATOR, 404",
            "/api/v1/ise/enforcement/restrict, ADMIN,    404",
            "/api/v1/ise/enforcement/clear,    VIEWER,   403",
            "/api/v1/ise/enforcement/clear,    ANALYST,  403",
            "/api/v1/ise/enforcement/clear,    OPERATOR, 404",
            "/api/v1/ise/posture/share,        VIEWER,   403",
            "/api/v1/ise/posture/share,        ANALYST,  404",
            "/api/v1/ise/posture/share,        OPERATOR, 404",
            "/api/v1/jobs,                     VIEWER,   403",
            "/api/v1/jobs,                     ANALYST,  404",
            "/api/v1/jobs,                     OPERATOR, 404",
    })
    void actionsRequireTheRightRole(String path, String role, int expected) throws Exception {
        assertEquals(expected, post(path, role));
    }

    @Test
    void policyEditIsAdminOnly() throws Exception {
        String body = "{\"requiredApps\":[],\"blockedApps\":[]}";
        for (String r : List.of("VIEWER", "ANALYST", "OPERATOR")) {
            assertEquals(403, mvc.perform(MockMvcRequestBuilders.put("/api/v1/policy/apps")
                    .with(SecurityMockMvcRequestPostProcessors.user("u").roles(r))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus(), "role " + r);
        }
    }

    @Test
    void userManagementIsAdminOnly() throws Exception {
        for (String r : List.of("VIEWER", "ANALYST", "OPERATOR")) {
            assertEquals(403, get("/api/v1/users", r), "role " + r);
        }
        assertEquals(200, get("/api/v1/users", "ADMIN"));
    }

    @Test
    void everyRoleCanStillRead() throws Exception {
        for (String r : List.of("VIEWER", "ANALYST", "OPERATOR", "ADMIN")) {
            assertEquals(200, get("/api/v1/endpoints", r), "role " + r);
            assertEquals(200, get("/api/v1/policy/apps", r), "role " + r);
        }
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        int status = mvc.perform(MockMvcRequestBuilders.get("/api/v1/endpoints"))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403);
    }
}