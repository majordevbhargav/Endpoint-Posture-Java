package com.endpointposture.isefeed;

import com.endpointposture.EndpointPostureApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may pull the feed and who may change the sharing rule. */
@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
@AutoConfigureMockMvc
class IseFeedRbacTest {

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

    static final String FEED = "/api/v1/integrations/ise/endpoints";
    static final String RULE = "/api/v1/integrations/ise/sharing-rule";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void reset() {
        jdbc.update("DELETE FROM ise_feed_audit");
        jdbc.update("DELETE FROM ise_sharing_rule WHERE version > 1");
        jdbc.update("UPDATE ise_sharing_rule SET active = true WHERE version = 1");
    }

    private String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private int statusOf(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    @Test
    void theFeedNeedsTheIseCredentialOrAnAdmin() throws Exception {
        int none = statusOf(MockMvcRequestBuilders.get(FEED));
        assertTrue(none == 401 || none == 403);
        assertEquals(401, statusOf(MockMvcRequestBuilders.get(FEED).header("Authorization", basic("ise-feed", "wrong"))));
        assertEquals(200, statusOf(MockMvcRequestBuilders.get(FEED).header("Authorization", basic("ise-feed", "feed-test-password"))));
        assertEquals(200, statusOf(MockMvcRequestBuilders.get(FEED).with(SecurityMockMvcRequestPostProcessors.user("a").roles("ADMIN"))));
        for (String role : List.of("VIEWER", "ANALYST", "OPERATOR")) {
            assertEquals(403, statusOf(MockMvcRequestBuilders.get(FEED).with(SecurityMockMvcRequestPostProcessors.user("u").roles(role))), role);
        }
    }

    @Test
    void theIseCredentialOpensNothingElse() throws Exception {
        String h = basic("ise-feed", "feed-test-password");
        for (String path : List.of("/api/v1/endpoints", RULE, "/api/v1/jobs", "/api/v1/users")) {
            int s = statusOf(MockMvcRequestBuilders.get(path).header("Authorization", h));
            assertTrue(s == 401 || s == 403, path + " returned " + s);
        }
    }

    @Test
    void onlyAdminsChangeTheRuleAndABadScopeIsA400() throws Exception {
        String body = "{\"scope\":\"CONNECTED\"}";
        for (String role : List.of("VIEWER", "ANALYST", "OPERATOR")) {
            assertEquals(403, statusOf(MockMvcRequestBuilders.put(RULE)
                    .with(SecurityMockMvcRequestPostProcessors.user("u").roles(role))
                    .contentType(MediaType.APPLICATION_JSON).content(body)), role);
        }
        mvc.perform(MockMvcRequestBuilders.put(RULE)
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("CONNECTED"))
                .andExpect(jsonPath("$.createdBy").value("root"));
        mvc.perform(MockMvcRequestBuilders.put(RULE)
                        .with(SecurityMockMvcRequestPostProcessors.user("root").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"EVERYTHING\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyRoleCanReadTheRule() throws Exception {
        for (String role : List.of("VIEWER", "ANALYST", "OPERATOR", "ADMIN")) {
            assertEquals(200, statusOf(MockMvcRequestBuilders.get(RULE).with(SecurityMockMvcRequestPostProcessors.user("u").roles(role))), role);
        }
    }
}