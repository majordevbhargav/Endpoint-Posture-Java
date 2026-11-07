package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CompletedJobResultTest {
    private static final UUID ID=UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private CompletedJobResult result() throws Exception {
        var mapper=new ObjectMapper();
        var claim=new ClaimedJob(ID,2,"HARDWARE_CHECK",mapper.readTree("{}"),Instant.now().plusSeconds(120));
        var job=new ValidatedJob(ID,2,"HARDWARE_CHECK","hardware_health_agent.ps1",List.of("-OutputOnly"),java.time.Duration.ofSeconds(60));
        var envelope=mapper.readTree("{\"schemaVersion\":1,\"jobId\":\""+ID+"\",\"jobType\":\"HARDWARE_CHECK\",\"collectionOutcome\":\"PARTIAL\",\"reportPayload\":{\"hardware\":{\"serial\":\"abc\"}},\"errors\":[],\"warnings\":[\"battery unavailable\"]}");
        return new CompletedJobResult(claim,job,7,envelope,false,false);
    }

    @Test void retainsFullEnvelopeAndIdentityAfterHandoff() throws Exception {
        var handoff=new InMemoryJobResultHandoff();
        var expected=result();
        handoff.accept(expected);
        var actual=handoff.poll();
        assertNotNull(actual);
        assertEquals(ID,actual.claimedJob().jobId());
        assertEquals("HARDWARE_CHECK",actual.validatedJob().jobType());
        assertEquals(7,actual.processExitCode());
        assertEquals("PARTIAL",actual.envelope().path("collectionOutcome").asText());
        assertEquals("abc",actual.envelope().path("reportPayload").path("hardware").path("serial").asText());
        assertEquals("battery unavailable",actual.envelope().path("warnings").get(0).asText());
    }

    @Test void envelopeAccessorReturnsDefensiveCopy() throws Exception {
        var completed=result();
        ((com.fasterxml.jackson.databind.node.ObjectNode) completed.envelope().path("reportPayload").path("hardware")).put("serial","mutated");
        assertEquals("abc",completed.envelope().path("reportPayload").path("hardware").path("serial").asText());
    }

    @Test void boundedHandoffNeverSilentlyDropsReports() throws Exception {
        var handoff=new InMemoryJobResultHandoff(1);
        handoff.accept(result());
        assertThrows(IllegalStateException.class,()->handoff.accept(result()));
        assertEquals(1,handoff.size());
    }

    @Test void rejectsIdentityMismatch() throws Exception {
        var valid=result();
        var bad=new ObjectMapper().readTree(valid.envelope().toString().replace(ID.toString(),"00000000-0000-0000-0000-000000000000"));
        assertThrows(IllegalArgumentException.class,()->new CompletedJobResult(valid.claimedJob(),valid.validatedJob(),0,bad,false,false));
    }


    @Test void handlerRemainsBusyUntilItsInMemoryResultIsDrained() throws Exception {
        var handoff = new InMemoryJobResultHandoff();
        handoff.accept(result());
        var config = new PowerShellExecutionConfig(false, "powershell.exe",
                java.nio.file.Path.of("scripts").toAbsolutePath(), java.time.Duration.ofSeconds(15), 1024 * 1024);
        var handler = new PowerShellJobHandler(java.net.URI.create("http://localhost:8090"), config,
                System.err, handoff);

        assertTrue(handler.isBusy(), "queued report must block further claims");
        assertNotNull(handler.localResultHandoff().poll());
        assertFalse(handler.isBusy(), "claiming can resume after the report is drained");
    }

}
