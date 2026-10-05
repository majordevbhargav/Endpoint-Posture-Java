package com.endpointposture.job;

import com.endpointposture.diagnostic.DiagnosticService;
import com.endpointposture.diagnostic.config.DiagnosticAgentProperties;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.indicator.SecurityIndicatorService;
import com.endpointposture.indicator.config.SecurityIndicatorAgentProperties;
import com.endpointposture.policy.PolicyService;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.config.PostureAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** S5: JobWorker builds the command and reads the result; the AgentRunner does the launching. */
class JobWorkerDispatchTest {

    JobService jobService = mock(JobService.class);
    AssessmentService assessmentService = mock(AssessmentService.class);
    PolicyService policyService = mock(PolicyService.class);
    AgentRunner runner = mock(AgentRunner.class);

    JobWorker worker;
    PostureJob job;
    final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        Path script = Files.createFile(dir.resolve("posture_agent.ps1"));

        PostureAgentProperties posture = new PostureAgentProperties();
        posture.setScriptPath(script.toString());
        posture.setServerUrl("http://localhost:8090/api/v1/posture");
        posture.setApiKey("secret-key");

        worker = new JobWorker(jobService, assessmentService, mock(HardwareHealthService.class),
                mock(DiagnosticService.class), mock(SecurityIndicatorService.class),
                posture, new HardwareAgentProperties(), new DiagnosticAgentProperties(),
                new SecurityIndicatorAgentProperties(), policyService, runner, new ObjectMapper());

        when(policyService.getActive()).thenReturn(new PolicyService.PolicySnapshot(
                UUID.randomUUID(), "p", 3, List.of("Cisco Secure Client"), List.of("uTorrent"), "admin", Instant.now()));

        Endpoint ep = Endpoint.builder().id(endpointId).macAddress("AA:BB:CC:DD:EE:FF").ipAddress("10.0.0.5").build();
        job = PostureJob.builder().id(UUID.randomUUID()).endpoint(ep)
                .jobType(JobType.POSTURE_CHECK).status(JobStatus.RUNNING).build();
        when(jobService.claimNextJob()).thenReturn(Optional.of(job));
    }

    @Test
    void submittedResultCompletesTheJobAndTheCommandCarriesTargetPolicyAndKey() throws Exception {
        when(runner.run(any())).thenReturn(new AgentRunResult(0, false, "noise\nRESULT_JSON:{\"submitted\":true}\n"));

        assertTrue(worker.runOnce());

        org.mockito.ArgumentCaptor<AgentCommand> c = org.mockito.ArgumentCaptor.forClass(AgentCommand.class);
        verify(runner).run(c.capture());
        List<String> cmd = c.getValue().command();
        assertEquals("10.0.0.5", cmd.get(cmd.indexOf("-ComputerName") + 1));
        assertEquals("3", cmd.get(cmd.indexOf("-PolicyVersion") + 1));
        assertEquals("uTorrent", cmd.get(cmd.indexOf("-BlockedAppsList") + 1));
        assertEquals(60, c.getValue().timeoutSeconds());
        assertEquals("secret-key", c.getValue().environment().get("POSTURE_API_KEY"));
        assertFalse(cmd.contains("secret-key"), "the key must never be on the command line");

        verify(jobService).markComplete(job.getId());
        verify(jobService, never()).markFailed(any(), anyString());
    }

    @Test
    void noResultLineFailsTheJobAndWritesFailureEvidence() throws Exception {
        when(runner.run(any())).thenReturn(new AgentRunResult(-1, true, "hung"));

        worker.runOnce();

        verify(assessmentService).recordFailure(eq(endpointId), eq(job.getId()), anyString());
        verify(jobService).markFailed(eq(job.getId()), anyString());
        verify(jobService, never()).markComplete(any());
    }

    @Test
    void notSubmittedFailsTheJobWithTheAgentsOwnDetail() throws Exception {
        when(runner.run(any())).thenReturn(new AgentRunResult(1, false,
                "RESULT_JSON:{\"submitted\":false,\"detail\":\"WinRM refused\"}\n"));

        worker.runOnce();

        verify(jobService).markFailed(job.getId(), "WinRM refused");
    }

    @Test
    void aRunnerThatCannotStartFailsTheJobInsteadOfThrowing() throws Exception {
        when(runner.run(any())).thenThrow(new java.io.IOException("powershell.exe not found"));

        worker.runOnce();

        verify(jobService).markFailed(eq(job.getId()), org.mockito.ArgumentMatchers.contains("powershell.exe not found"));
    }

    @Test
    void emptyQueueRunsNothing() {
        when(jobService.claimNextJob()).thenReturn(Optional.empty());
        assertFalse(worker.runOnce());
        verifyNoInteractions(runner);
    }
}