package com.endpointposture.job;

import com.endpointposture.diagnostic.config.DiagnosticAgentProperties;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.indicator.config.SecurityIndicatorAgentProperties;
import com.endpointposture.job.spec.DiagnosticSpec;
import com.endpointposture.job.spec.HardwareSpec;
import com.endpointposture.job.spec.JobSpec;
import com.endpointposture.job.spec.PostureSpec;
import com.endpointposture.job.spec.SecuritySpec;
import com.endpointposture.posture.config.PostureAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns claimed jobs into typed agent runs. {@link JobWorkerPool} calls
 * {@link #runOnce()} from several threads. The runner controls where/how a
 * command executes; this class interprets the result.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final String RESULT_PREFIX = "RESULT_JSON:";
    private static final String API_KEY_ENV_VAR = "POSTURE_API_KEY";

    private final JobService jobService;
    private final JobOutcomeService outcomes;
    private final JobSpecFactory specFactory;
    private final PostureAgentProperties postureProps;
    private final HardwareAgentProperties hardwareProps;
    private final DiagnosticAgentProperties diagnosticProps;
    private final SecurityIndicatorAgentProperties securityProps;
    private final AgentRunner agentRunner;
    private final ObjectMapper objectMapper;

    public JobWorker(JobService jobService, JobOutcomeService outcomes, JobSpecFactory specFactory,
                     PostureAgentProperties postureProps, HardwareAgentProperties hardwareProps,
                     DiagnosticAgentProperties diagnosticProps, SecurityIndicatorAgentProperties securityProps,
                     AgentRunner agentRunner, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.outcomes = outcomes;
        this.specFactory = specFactory;
        this.postureProps = postureProps;
        this.hardwareProps = hardwareProps;
        this.diagnosticProps = diagnosticProps;
        this.securityProps = securityProps;
        this.agentRunner = agentRunner;
        this.objectMapper = objectMapper;
    }

    /** @return true if a job was claimed and run, false if the queue had nothing eligible */
    public boolean runOnce() {
        var claimed = jobService.claimNextJob();
        claimed.ifPresent(this::dispatch);
        return claimed.isPresent();
    }

    private void dispatch(PostureJob job) {
        Endpoint endpoint = job.getEndpoint();
        try {
            JobSpec spec = specFactory.build(job, endpoint);
            handleResult(job, endpoint, runAgent(spec));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(job, endpoint, "Dispatch interrupted");
        } catch (Exception e) {
            log.error("Dispatch failed for job {}", job.getId(), e);
            fail(job, endpoint, "Dispatch error: " + e.getMessage());
        }
    }

    private void handleResult(PostureJob job, Endpoint endpoint, RunResult result) {
        JsonNode json = result.resultJson();
        if (json == null) {
            fail(job, endpoint, "No RESULT_JSON (exit=" + result.exitCode()
                    + ", timedOut=" + result.timedOut() + "). Last output: " + truncate(result.rawOutput()));
            return;
        }

        if (!json.path("submitted").asBoolean(false)) {
            String detail = json.path("detail").asText(null);
            String submitError = json.path("submitError").asText(null);
            fail(job, endpoint, detail != null ? detail : "Submission failed: " + submitError);
            return;
        }

        outcomes.complete(job.getId());
    }

    private void fail(PostureJob job, Endpoint endpoint, String reason) {
        outcomes.fail(job.getJobType(), job.getId(), endpoint.getId(), reason);
    }

    private RunResult runAgent(JobSpec spec) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", resolveScript(scriptFor(spec))));
        command.addAll(AgentArgs.of(spec, serverUrlFor(spec)));
        return execute(command, spec.processTimeoutSeconds());
    }

    private String scriptFor(JobSpec spec) {
        return switch (spec) {
            case PostureSpec ignored -> postureProps.getScriptPath();
            case HardwareSpec ignored -> hardwareProps.getScriptPath();
            case DiagnosticSpec ignored -> diagnosticProps.getScriptPath();
            case SecuritySpec ignored -> securityProps.getScriptPath();
        };
    }

    private String serverUrlFor(JobSpec spec) {
        return switch (spec) {
            case PostureSpec ignored -> postureProps.getServerUrl();
            case HardwareSpec ignored -> hardwareProps.getServerBaseUrl();
            case DiagnosticSpec ignored -> diagnosticProps.getServerBaseUrl();
            case SecuritySpec ignored -> securityProps.getServerBaseUrl();
        };
    }

    /** Hands the command to the runner and parses the RESULT_JSON line from its output. */
    private RunResult execute(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        AgentRunResult raw = agentRunner.run(new AgentCommand(command, timeoutSeconds, agentEnv()));
        return new RunResult(raw.exitCode(), raw.timedOut(), raw.rawOutput(), extractResultJson(raw.rawOutput()));
    }

    private Map<String, String> agentEnv() {
        return postureProps.getApiKey() == null || postureProps.getApiKey().isBlank()
                ? Map.of()
                : Map.of(API_KEY_ENV_VAR, postureProps.getApiKey());
    }

    private String resolveScript(String configured) throws IOException {
        if (configured == null || configured.isBlank()) {
            throw new IOException("Agent script path is not configured");
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Agent script not found: " + path);
        }
        return path.toString();
    }

    /**
     * Finds the last RESULT_JSON line in agent output.
     * @return parsed JSON, or null when absent or malformed
     */
    JsonNode extractResultJson(String output) {
        JsonNode found = null;
        for (String line : output.split("\\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(RESULT_PREFIX)) {
                try {
                    found = objectMapper.readTree(trimmed.substring(RESULT_PREFIX.length()));
                } catch (Exception e) {
                    log.warn("Could not parse RESULT_JSON line: {}", trimmed, e);
                    found = null;
                }
            }
        }
        return found;
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() > 500 ? value.substring(0, 500) + "..." : value;
    }

    private record RunResult(int exitCode, boolean timedOut, String rawOutput, JsonNode resultJson) {}
}
