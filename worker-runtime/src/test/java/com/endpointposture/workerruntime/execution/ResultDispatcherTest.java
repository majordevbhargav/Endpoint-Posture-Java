package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.TestServer;
import com.endpointposture.workerruntime.WorkerConfig;
import com.endpointposture.workerruntime.api.WorkerApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResultDispatcherTest {
    @TempDir Path dir;
    private final UUID job=UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private CompletedJobResult result() throws Exception {
        var json=com.endpointposture.workerruntime.api.Json.mapper();
        var claim=new com.endpointposture.workerruntime.api.ClaimedJob(job,1,"DIAGNOSTIC_CHECK",
                json.readTree("{}"),Instant.now().plusSeconds(300));
        var valid=new ValidatedJob(job,1,"DIAGNOSTIC_CHECK","diagnostic_agent.ps1",List.of("-OutputOnly"),Duration.ofSeconds(30));
        return new CompletedJobResult(claim,valid,0,json.readTree("{\"schemaVersion\":1,\"jobId\":\""+job+"\",\"jobType\":\"DIAGNOSTIC_CHECK\",\"collectionOutcome\":\"SUCCESS\",\"reportPayload\":{\"status\":\"OK\"},\"errors\":[],\"warnings\":[]}"),false,false);
    }
    private boolean awaitState(SqliteJobResultHandoff store,String expected) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end) { if(expected.equals(store.state(job,1))) return true; Thread.sleep(20); }
        return false;
    }

    @Test void sendsStoredPayloadAndMarksDeliveredOnlyAfterMatchingAcknowledgement() throws Exception {
        String ack="{\"accepted\":true,\"receiptId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"jobId\":\""+job+"\",\"attempt\":1,\"collectionOutcome\":\"SUCCESS\",\"acceptedAt\":\"2026-11-11T12:00:00Z\"}";
        try(var server=new TestServer();var store=new SqliteJobResultHandoff(dir.resolve("outbox.sqlite"),UUID.randomUUID(),"s",Duration.ofMinutes(2))) {
            server.on("/api/v1/workers/jobs/"+job+"/result",200,ack);
            store.accept(result()); byte[] payload=store.pending(1).get(0).requestPayload();
            var config=new WorkerConfig(server.uri(),dir.resolve("state.json"),null,Duration.ofSeconds(1),Duration.ofSeconds(2));
            try(var dispatcher=new ResultDispatcher(store,WorkerApiClient.authenticated(config,"epw_test_key"),System.err,0)) {
                assertTrue(awaitState(store,"DELIVERED"));
            }
            var resultRequest=server.requests.stream().filter(r->r.path().endsWith("/result")).findFirst().orElseThrow();
            assertEquals("epw_test_key",resultRequest.workerKey());
            assertArrayEquals(payload,resultRequest.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(store.hasPendingResults());
        }
    }

    @Test void arbitrary409IsRetainedAsPermanentFailureAndContinuesToBlockClaims() throws Exception {
        try(var server=new TestServer();var store=new SqliteJobResultHandoff(dir.resolve("outbox.sqlite"),UUID.randomUUID(),"s",Duration.ofMinutes(2))) {
            server.on("/api/v1/workers/jobs/"+job+"/result",409,"{\"message\":\"conflict\"}");
            store.accept(result());
            var config=new WorkerConfig(server.uri(),dir.resolve("state.json"),null,Duration.ofSeconds(1),Duration.ofSeconds(2));
            try(var dispatcher=new ResultDispatcher(store,WorkerApiClient.authenticated(config,"epw_test_key"),System.err,0)) {
                assertTrue(awaitState(store,"PERMANENT_FAILURE"));
            }
            assertTrue(store.hasPendingResults());
        }
    }

    @Test void malformedAcknowledgementDoesNotMarkResultDelivered() throws Exception {
        try(var server=new TestServer();var store=new SqliteJobResultHandoff(dir.resolve("outbox.sqlite"),UUID.randomUUID(),"s",Duration.ofMinutes(2))) {
            server.on("/api/v1/workers/jobs/"+job+"/result",200,"{\"accepted\":true}");
            store.accept(result());
            var config=new WorkerConfig(server.uri(),dir.resolve("state.json"),null,Duration.ofSeconds(1),Duration.ofSeconds(2));
            try(var dispatcher=new ResultDispatcher(store,WorkerApiClient.authenticated(config,"epw_test_key"),System.err,0)) {
                assertTrue(awaitState(store,"PERMANENT_FAILURE"));
                assertTrue(server.requests.stream().anyMatch(r->r.path().endsWith("/result")));
                assertEquals("PERMANENT_FAILURE",store.state(job,1));
            }
            assertTrue(store.hasPendingResults());
        }
    }

    @Test void transientFailureRetriesTheIdenticalRequestAndHonorsRetryAfter() throws Exception {
        String ack="{\"accepted\":true,\"receiptId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"jobId\":\""+job+"\",\"attempt\":1,\"collectionOutcome\":\"SUCCESS\",\"acceptedAt\":\"2026-11-11T12:00:00Z\"}";
        try(var server=new TestServer();var store=new SqliteJobResultHandoff(dir.resolve("outbox.sqlite"),UUID.randomUUID(),"s",Duration.ofMinutes(2))) {
            server.onSequence("/api/v1/workers/jobs/"+job+"/result",new int[]{429,200},new String[]{"{\"message\":\"slow down\"}",ack},java.util.Map.of("Retry-After","1"));
            store.accept(result()); byte[] expected=store.pending(1).get(0).requestPayload();
            var config=new WorkerConfig(server.uri(),dir.resolve("state.json"),null,Duration.ofSeconds(1),Duration.ofSeconds(2));
            try(var dispatcher=new ResultDispatcher(store,WorkerApiClient.authenticated(config,"epw_test_key"),System.err,0)) {
                assertTrue(awaitState(store,"DELIVERED"));
            }
            var posts=server.requests.stream().filter(r->r.path().endsWith("/result")).toList();
            assertEquals(2,posts.size());
            assertEquals(posts.get(0).body(),posts.get(1).body());
            assertArrayEquals(expected,posts.get(0).body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void leaseRenewalIsBoundedByConfiguredCumulativeMaximum() throws Exception {
        String ack="{\"accepted\":true,\"receiptId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"jobId\":\""+job+"\",\"attempt\":1,\"collectionOutcome\":\"SUCCESS\",\"acceptedAt\":\"2026-11-11T12:00:00Z\"}";
        try(var server=new TestServer();var store=new SqliteJobResultHandoff(dir.resolve("outbox.sqlite"),UUID.randomUUID(),"s",Duration.ofMinutes(2))) {
            server.on("/api/v1/workers/jobs/"+job+"/lease/renew",200,"{\"accepted\":true,\"leaseExpiresAt\":\"2026-11-11T12:10:00Z\"}");
            server.on("/api/v1/workers/jobs/"+job+"/result",200,ack);
            store.accept(result());
            var config=new WorkerConfig(server.uri(),dir.resolve("state.json"),null,Duration.ofSeconds(1),Duration.ofSeconds(2));
            try(var dispatcher=new ResultDispatcher(store,WorkerApiClient.authenticated(config,"epw_test_key"),System.err,75)) {
                assertTrue(awaitState(store,"DELIVERED"));
            }
            var renew=server.requests.stream().filter(r->r.path().endsWith("/lease/renew")).findFirst().orElseThrow();
            assertEquals(75,com.endpointposture.workerruntime.api.Json.mapper().readTree(renew.body()).path("extensionSeconds").asInt());
        }
    }

    @Test void persistedLeaseCapIsNotResetByDeliveryRetry() throws Exception {
        String ack="{\"accepted\":true,\"receiptId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"jobId\":\""+job+"\",\"attempt\":1,\"collectionOutcome\":\"SUCCESS\",\"acceptedAt\":\"2026-11-11T12:00:00Z\"}";
        try(var server=new TestServer();var store=new SqliteJobResultHandoff(dir.resolve("outbox.sqlite"),UUID.randomUUID(),"s",Duration.ofMinutes(2))) {
            server.on("/api/v1/workers/jobs/"+job+"/lease/renew",200,"{\"accepted\":true,\"leaseExpiresAt\":\"2026-11-11T12:10:00Z\"}");
            server.onSequence("/api/v1/workers/jobs/"+job+"/result",new int[]{503,200},new String[]{"{}",ack});
            store.accept(result());
            var config=new WorkerConfig(server.uri(),dir.resolve("state.json"),null,Duration.ofSeconds(1),Duration.ofSeconds(2));
            try(var dispatcher=new ResultDispatcher(store,WorkerApiClient.authenticated(config,"epw_test_key"),System.err,75)) {
                assertTrue(awaitState(store,"DELIVERED"));
            }
            assertEquals(1,server.requests.stream().filter(r->r.path().endsWith("/lease/renew")).count());
        }
    }

    @Test void expiredDeliveryGraceDoesNotRenewLease() throws Exception {
        String ack="{\"accepted\":true,\"receiptId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"jobId\":\""+job+"\",\"attempt\":1,\"collectionOutcome\":\"SUCCESS\",\"acceptedAt\":\"2026-11-11T12:00:00Z\"}";
        try(var server=new TestServer();var store=new SqliteJobResultHandoff(dir.resolve("outbox.sqlite"),UUID.randomUUID(),"s",Duration.ofMillis(1))) {
            server.on("/api/v1/workers/jobs/"+job+"/result",200,ack);
            store.accept(result()); Thread.sleep(20);
            var config=new WorkerConfig(server.uri(),dir.resolve("state.json"),null,Duration.ofSeconds(1),Duration.ofSeconds(2));
            try(var dispatcher=new ResultDispatcher(store,WorkerApiClient.authenticated(config,"epw_test_key"),System.err,900)) {
                assertTrue(awaitState(store,"DELIVERED"));
            }
            assertTrue(server.requests.stream().noneMatch(r->r.path().endsWith("/lease/renew")));
        }
    }
}
