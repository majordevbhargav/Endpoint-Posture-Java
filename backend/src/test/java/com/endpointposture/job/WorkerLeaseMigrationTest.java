package com.endpointposture.job;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V25 must apply on top of a database that already holds jobs, leave those jobs
 * intact, and add the two lease columns as nullable. Migrates to V24 first,
 * inserts a job, then applies the rest, so this is a real upgrade path rather
 * than a fresh install.
 */
@Testcontainers
class WorkerLeaseMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("endpoint_posture_test").withUsername("test").withPassword("test");

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void v25AddsNullableLeaseColumnsAndKeepsExistingJobs() throws Exception {
        flyway("24").migrate();

        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = c.createStatement()) {

            st.execute("INSERT INTO endpoint (mac_address) VALUES ('AA:BB:CC:00:00:01')");
            st.execute("""
                    INSERT INTO posture_job (endpoint_id, status, attempt_count)
                    SELECT id, 'RUNNING', 2 FROM endpoint WHERE mac_address = 'AA:BB:CC:00:00:01'
                    """);

            flyway("latest").migrate();

            try (ResultSet rs = st.executeQuery("""
                    SELECT column_name, data_type, is_nullable FROM information_schema.columns
                     WHERE table_name = 'posture_job'
                       AND column_name IN ('claimed_by', 'lease_expires_at')
                     ORDER BY column_name
                    """)) {
                assertTrue(rs.next());
                assertEquals("claimed_by", rs.getString("column_name"));
                assertEquals("text", rs.getString("data_type"));
                assertEquals("YES", rs.getString("is_nullable"));
                assertTrue(rs.next());
                assertEquals("lease_expires_at", rs.getString("column_name"));
                assertEquals("timestamp with time zone", rs.getString("data_type"));
                assertEquals("YES", rs.getString("is_nullable"));
                assertFalse(rs.next());
            }

            try (ResultSet rs = st.executeQuery(
                    "SELECT status, attempt_count, claimed_by, lease_expires_at FROM posture_job")) {
                assertTrue(rs.next());
                assertEquals("RUNNING", rs.getString("status"));
                assertEquals(2, rs.getInt("attempt_count"));
                assertNull(rs.getString("claimed_by"));
                assertNull(rs.getTimestamp("lease_expires_at"));
                assertFalse(rs.next());
            }
        }
    }
}
