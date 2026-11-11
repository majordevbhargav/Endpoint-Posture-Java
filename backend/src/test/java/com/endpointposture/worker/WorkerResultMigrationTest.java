package com.endpointposture.worker;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the complete migration path through V29 on PostgreSQL 16. */
@Testcontainers
class WorkerResultMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("endpoint_posture_test").withUsername("test").withPassword("test");

    @Test
    void migratesThroughV29AndReceiptSurvivesJobDeletion() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID endpointId;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO endpoint(mac_address) VALUES (?) RETURNING id")) {
                insert.setString(1, "02:00:00:00:00:29");
                try (ResultSet rs = insert.executeQuery()) {
                    assertTrue(rs.next());
                    endpointId = rs.getObject(1, UUID.class);
                }
            }

            UUID jobId;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO posture_job(endpoint_id, job_type, status, priority, attempt_count, max_attempts)
                    VALUES (?, 'POSTURE_CHECK', 'QUEUED', 0, 0, 3) RETURNING id
                    """)) {
                insert.setObject(1, endpointId);
                try (ResultSet rs = insert.executeQuery()) {
                    assertTrue(rs.next());
                    jobId = rs.getObject(1, UUID.class);
                }
            }

            UUID receiptId = UUID.randomUUID();
            UUID workerId = UUID.randomUUID();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO worker_job_result_receipt
                      (id, job_id, attempt, worker_id, result_hash, schema_version, collection_outcome)
                    VALUES (?, ?, 1, ?, ?, 1, 'SUCCESS')
                    """)) {
                insert.setObject(1, receiptId);
                insert.setObject(2, jobId);
                insert.setObject(3, workerId);
                insert.setString(4, "a".repeat(64));
                insert.executeUpdate();
            }

            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM posture_job WHERE id = ?")) {
                delete.setObject(1, jobId);
                assertEquals(1, delete.executeUpdate());
            }

            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT job_id, worker_id FROM worker_job_result_receipt WHERE id = ?")) {
                select.setObject(1, receiptId);
                try (ResultSet receipt = select.executeQuery()) {
                    assertTrue(receipt.next());
                    assertEquals(jobId, receipt.getObject("job_id", UUID.class));
                    assertEquals(workerId, receipt.getObject("worker_id", UUID.class));
                }
            }
        }
    }
}
