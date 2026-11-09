package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PowerShellJobHandlerFailureTest {
    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ClaimedJob claim() throws Exception {
        return new ClaimedJob(ID, 1, "DIAGNOSTIC_CHECK",
                JSON.readTree("{\"jobId\":\"" + ID + "\",\"target\":\"host1\"}"),
                Instant.now().plusSeconds(300));
    }

    private static ValidatedJob validated() {
        return new ValidatedJob(ID, 1, "DIAGNOSTIC_CHECK", "diagnostic_agent.ps1",
                java.util.List.of("-OutputOnly"), Duration.ofSeconds(10));
    }

    private static CompletedJobResult completed() throws Exception {
        return new CompletedJobResult(claim(), validated(), 0,
                JSON.readTree("{\"schemaVersion\":1,\"jobId\":\"" + ID
                        + "\",\"jobType\":\"DIAGNOSTIC_CHECK\",\"collectionOutcome\":\"SUCCESS\","
                        + "\"reportPayload\":{},\"errors\":[],\"warnings\":[]}"), false, false);
    }

    private static PowerShellJobHandler handler(PowerShellJobHandler.JobParser parser,
                                                PowerShellJobHandler.JobExecutor executor,
                                                InMemoryJobResultHandoff handoff,
                                                ByteArrayOutputStream logs) {
        return new PowerShellJobHandler(parser, executor, new PrintStream(logs), handoff);
    }

    private static void awaitBlocked(PowerShellJobHandler handler, ByteArrayOutputStream logs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && !logs.toString().contains("CRITICAL: worker blocked")) {
            Thread.sleep(10);
        }
        assertTrue(logs.toString().contains("CRITICAL: worker blocked"),
                "failure must reach the sticky blocked state");
        assertTrue(handler.isBusy(), "failed claimed job must prevent further claims");
    }

    @Test void parseFailurePermanentlyBlocksClaims() throws Exception {
        var logs = new ByteArrayOutputStream();
        var handler = handler(job -> { throw new IllegalArgumentException("bad spec"); },
                (job, spec) -> fail("executor must not run"), new InMemoryJobResultHandoff(), logs);
        handler.handle(claim());
        awaitBlocked(handler, logs);
        handler.close();
    }

    @Test void executionFailurePermanentlyBlocksClaims() throws Exception {
        var logs = new ByteArrayOutputStream();
        var handler = handler(job -> validated(),
                (job, spec) -> { throw new IllegalStateException("process start failed"); },
                new InMemoryJobResultHandoff(), logs);
        handler.handle(claim());
        awaitBlocked(handler, logs);
        handler.close();
    }

    @Test void interruptionPermanentlyBlocksClaims() throws Exception {
        var logs = new ByteArrayOutputStream();
        var handler = handler(job -> validated(),
                (job, spec) -> { throw new InterruptedException("cancelled"); },
                new InMemoryJobResultHandoff(), logs);
        handler.handle(claim());
        awaitBlocked(handler, logs);
        handler.close();
    }

    @Test void queueOverflowPermanentlyBlocksClaimsAndPreservesExistingResult() throws Exception {
        var logs = new ByteArrayOutputStream();
        var handoff = new InMemoryJobResultHandoff(1);
        handoff.accept(completed());
        var handler = handler(job -> validated(),
                (job, spec) -> new PowerShellExecutor.ExecutionResult(0, completed().envelope(), false, false),
                handoff, logs);
        handler.handle(claim());
        awaitBlocked(handler, logs);
        assertEquals(1, handoff.size());
        assertNotNull(handoff.poll());
        handler.close();
    }

    @Test void successfulHandoffDoesNotSetBlockedState() throws Exception {
        var handoff = new InMemoryJobResultHandoff();
        var result = completed();
        var handler = handler(job -> validated(),
                (job, spec) -> new PowerShellExecutor.ExecutionResult(0, result.envelope(), false, false),
                handoff, new ByteArrayOutputStream());
        handler.handle(claim());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (handoff.size() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, handoff.size());
        assertTrue(handler.isBusy(), "pending result blocks claims but is not a failure block");
        assertNotNull(handoff.poll());
        assertFalse(handler.isBusy());
        handler.close();
    }
}