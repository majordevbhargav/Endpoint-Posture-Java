package com.endpointposture.security;

import com.endpointposture.EndpointPostureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Drives the real login route to prove lockout, reset and non-disclosure. */
@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
@AutoConfigureMockMvc
class AuthLockoutTest {

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

    private static final String GOOD = "a-good-password-123";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    private User newUser(String name) {
        return users.save(User.builder().username(name).passwordHash(encoder.encode(GOOD))
                .role(Role.VIEWER).enabled(true).build());
    }

    private String loginBody(String user, String pw) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}";
    }

    private int login(String user, String pw) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(loginBody(user, pw)))
                .andReturn().getResponse().getStatus();
    }

    private String loginResponseBody(String user, String pw) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(loginBody(user, pw)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenAgainstTheCorrectPassword() throws Exception {
        newUser("lock-victim");
        for (int i = 0; i < 5; i++) assertEquals(401, login("lock-victim", "wrong-password-1"));

        assertEquals(401, login("lock-victim", GOOD));
        assertEquals(true, users.findByUsername("lock-victim").orElseThrow().getLockedUntil().isAfter(Instant.now()));
    }

    @Test
    void aSuccessfulLoginResetsTheCounter() throws Exception {
        newUser("lock-reset");
        for (int i = 0; i < 4; i++) assertEquals(401, login("lock-reset", "wrong-password-1"));
        assertEquals(200, login("lock-reset", GOOD));

        for (int i = 0; i < 4; i++) assertEquals(401, login("lock-reset", "wrong-password-1"));
        assertEquals(200, login("lock-reset", GOOD)); // 4 + 4 failures, never 5 in a row
    }

    @Test
    void anExpiredLockAllowsLoginAgain() throws Exception {
        User u = newUser("lock-expired");
        u.setLockedUntil(Instant.now().minusSeconds(1));
        users.save(u);

        assertEquals(200, login("lock-expired", GOOD));
    }

    @Test
    void lockedUnknownAndWrongPasswordResponsesAreIdentical() throws Exception {
        newUser("lock-same");
        String wrong = loginResponseBody("lock-same", "wrong-password-1");
        String unknown = loginResponseBody("no-such-user", "wrong-password-1");
        for (int i = 0; i < 4; i++) login("lock-same", "wrong-password-1");
        String locked = loginResponseBody("lock-same", GOOD);

        assertEquals(wrong, unknown);
        assertEquals(wrong, locked);
    }

    @Test
    void aBlankUsernameIsRejectedAsBadInput() throws Exception {
        assertEquals(400, login("", "whatever"));
    }
}