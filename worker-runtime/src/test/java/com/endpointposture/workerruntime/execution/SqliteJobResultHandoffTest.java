package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SqliteJobResultHandoffTest {
    @TempDir Path dir;
    private final UUID worker=UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID job=UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private Path db(){return dir.resolve("worker-results.sqlite");}
    private SqliteJobResultHandoff store(){return new SqliteJobResultHandoff(db(),worker,"default",Duration.ofMinutes(15));}

    private CompletedJobResult result(String warning) throws Exception {
        var json=com.endpointposture.workerruntime.api.Json.mapper();
        var claim=new ClaimedJob(job,2,"HARDWARE_CHECK",json.readTree("{\"jobId\":\""+job+"\"}"),Instant.parse("2026-11-11T12:00:00Z"));
        var valid=new ValidatedJob(job,2,"HARDWARE_CHECK","hardware_health_agent.ps1",List.of("-OutputOnly"),Duration.ofSeconds(90));
        var env=(ObjectNode)json.readTree("{\"schemaVersion\":1,\"jobId\":\""+job+"\",\"jobType\":\"HARDWARE_CHECK\",\"collectionOutcome\":\"PARTIAL\",\"reportPayload\":{\"cpuMemory\":{},\"storage\":{}},\"errors\":[],\"warnings\":[\""+warning+"\"]}");
        return new CompletedJobResult(claim,valid,7,env,false,true);
    }

    @Test void initializesWalFullAndForeignKeys() {
        try(var s=store()) {
            assertEquals("wal",s.journalMode().toLowerCase());
            assertEquals(2,s.synchronousMode());
            assertTrue(s.foreignKeysEnabled());
            assertTrue(Files.exists(db()));
            assertEquals(0,s.statistics().pending());
        }
    }

    @Test void migratesPreviousSchemaVersionInOrder() throws Exception {
        try(var s=store()) { }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db());var st=c.createStatement()) {
            st.execute("DROP TRIGGER result_outbox_state_transition");
            st.execute("DROP TRIGGER result_outbox_pending_delete");
            st.execute("DROP TABLE worker_metadata");
            st.execute("PRAGMA user_version=1");
        }
        try(var s=store()) {
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+db());var st=c.createStatement();var r=st.executeQuery("PRAGMA user_version")) {
                assertTrue(r.next()); assertEquals(3,r.getInt(1));
            }
            assertNull(s.lastBackendSuccess());
        }
    }

    @Test void commitsSnapshotAndExactPayloadThenRecoversAfterReopen() throws Exception {
        byte[] original;
        try(var first=store()) {
            first.accept(result("battery unavailable"));
            var saved=first.pending(10).get(0);
            original=saved.requestPayload();
            assertTrue(new String(original).contains("\"attempt\":2"));
            assertTrue(new String(original).contains("battery unavailable"));
            assertEquals(1,first.pendingCount());
        }
        try(var restarted=store()) {
            var saved=restarted.pending(10).get(0);
            assertArrayEquals(original,saved.requestPayload());
            assertEquals(worker,saved.workerId());
            assertEquals("default",saved.shard());
            assertEquals(2,saved.result().claimedJob().attempt());
            assertEquals("HARDWARE_CHECK",saved.result().claimedJob().jobType());
            assertEquals("HARDWARE_CHECK",saved.result().validatedJob().jobType());
            assertEquals(7,saved.result().processExitCode());
            assertEquals("PARTIAL",saved.result().envelope().path("collectionOutcome").asText());
            assertEquals(Instant.parse("2026-11-11T12:00:00Z"),saved.result().claimedJob().leaseExpiresAt());
            assertTrue(saved.result().stderrTruncated());
        }
    }

    @Test void sameRecordIsIdempotentAndConflictingPayloadCannotOverwrite() throws Exception {
        try(var s=store()) {
            var good=result("battery unavailable");
            s.accept(good); s.accept(good);
            assertEquals(1,s.pendingCount());
            assertThrows(Exception.class,()->s.accept(result("different warning")));
            assertTrue(new String(s.pending(1).get(0).requestPayload()).contains("battery unavailable"));
        }
    }

    @Test void attemptUniquenessIsGlobalAndDoesNotPermitAnotherWorkerToClaimTheSameLocalAttempt() throws Exception {
        try(var first=store()) { first.accept(result("battery unavailable")); }
        try(var other=new SqliteJobResultHandoff(db(),UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),"default",Duration.ofMinutes(15))) {
            assertThrows(Exception.class,()->other.accept(result("battery unavailable")));
            assertEquals(1,other.statistics().pending());
        }
    }

    @Test void immutablePayloadTriggerRejectsUpdates() throws Exception {
        try(var s=store()) { s.accept(result("battery unavailable")); }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db());var st=c.createStatement()) {
            assertThrows(Exception.class,()->st.executeUpdate("UPDATE result_outbox SET request_payload=x'00'"));
        }
    }

    @Test void unresolvedRowsCannotBeDeletedAndStateTransitionsAreOneWay() throws Exception {
        try(var s=store()) { s.accept(result("battery unavailable")); }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db());var st=c.createStatement()) {
            assertThrows(Exception.class,()->st.executeUpdate("DELETE FROM result_outbox"));
            st.executeUpdate("UPDATE result_outbox SET state='PERMANENT_FAILURE'");
            assertThrows(Exception.class,()->st.executeUpdate("UPDATE result_outbox SET state='PENDING'"));
            assertThrows(Exception.class,()->st.executeUpdate("DELETE FROM result_outbox"));
        }
    }

    @Test void integrityHashDetectsTamperingEvenIfTriggerWasBypassed() throws Exception {
        try(var s=store()) { s.accept(result("battery unavailable")); }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db());var st=c.createStatement()) {
            st.execute("DROP TRIGGER result_outbox_immutable");
            st.executeUpdate("UPDATE result_outbox SET request_payload=x'00'");
        }
        try(var s=store()) { assertThrows(IllegalStateException.class,()->s.pending(1)); }
    }

    @Test void failedInsertRollsBackWithoutReportingHandoffSuccess() throws Exception {
        try(var s=store()) { }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db());var st=c.createStatement()) {
            st.execute("CREATE TRIGGER force_failure BEFORE INSERT ON result_outbox BEGIN SELECT RAISE(ABORT,'disk simulation'); END");
        }
        try(var s=store()) {
            assertThrows(Exception.class,()->s.accept(result("battery unavailable")));
            assertEquals(0,s.pendingCount());
        }
    }

    @Test void concurrentIdenticalAcceptsCreateOneRow() throws Exception {
        try(var s=store();var pool=Executors.newFixedThreadPool(4)) {
            var result=result("battery unavailable");
            var tasks=java.util.stream.IntStream.range(0,8).<Runnable>mapToObj(i->()->{
                try{s.accept(result);}catch(Exception e){throw new RuntimeException(e);}
            }).toList();
            for(var task:tasks) pool.submit(task);
            pool.shutdown();
            assertTrue(pool.awaitTermination(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,s.pendingCount());
        }
    }

    @Test void deliveredRowsDoNotBlockClaimsButPermanentFailuresRemainBlocking() throws Exception {
        try(var s=store()) {
            s.accept(result("battery unavailable"));
            assertTrue(s.hasPendingResults());
            s.markPermanentFailure(job,2,"operator review");
            assertTrue(s.hasPendingResults());
            assertEquals("PERMANENT_FAILURE",s.state(job,2));
        }
    }

    @Test void leaseExtensionBudgetIsPersistedBeforeRequestForCrashSafety() throws Exception {
        try(var s=store()) {
            s.accept(result("battery unavailable"));
            assertTrue(s.reserveLeaseExtension(job,2,0,75));
            assertEquals(75,s.pending(1).get(0).cumulativeLeaseExtensionSeconds());
            assertFalse(s.reserveLeaseExtension(job,2,0,75));
        }
        try(var restarted=store()) {
            assertEquals(75,restarted.pending(1).get(0).cumulativeLeaseExtensionSeconds());
        }
    }
}
