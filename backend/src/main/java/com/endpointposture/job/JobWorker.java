package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.HardwareHealthService;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.config.PostureAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Turns claimed jobs into real PowerShell runs. {@link JobWorkerPool} calls
 * {@link #runOnce()} from several threads.
 *
 * <h2>How one job flows</h2>
 * <ol>
 *   <li>Claim the next eligible job ({@code RUNNING}).</li>
 *   <li>Start the matching PowerShell agent with an outer timeout, capture output.</li>
 *   <li>The agent POSTs its own report and prints one {@code RESULT_JSON:} line.</li>
 *   <li>{@code submitted: true} means COMPLETE.</li>
 *   <li>Anything else writes a failure evidence row (ERROR assessment or
 *       succeeded=false hardware row) and fails the job (retry with backoff).</li>
 * </ol>
 *
 * <p>Nothing here calls Cisco ISE; dispatch is observation only.</p>
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private static final String RESULT_PREFIX = "RESULT_JSON:";
    private static final String API_KEY_ENV_VAR = "POSTURE_API_KEY";

    private final JobService jobService;
    private final AssessmentService assessmentService;
    private final HardwareHealthService hardwareHealthService;
    private final PostureAgentProperties postureProps;
    private final HardwareAgentProperties hardwareProps;
    private final ObjectMapper objectMapper;

    public JobWorker(JobService jobService,
                     AssessmentService assessmentService,
                     HardwareHealthService hardwareHealthService,
                     PostureAgentProperties postureProps,
                     HardwareAgentProperties hardwareProps,
                     ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.assessmentService = assessmentService;
        this.hardwareHealthService = hardwareHealthService;
        this.postureProps = postureProps;
        this.hardwareProps = hardwareProps;
        this.objectMapper = objectMapper;
    }

    /**
     * Claims and runs at most one job.
     *
     * @return true if a job was claimed (the caller should poll again immediately)
     */
    public boolean runOnce() {
        var claimed = jobService.claimNextJob();
        claimed.ifPresent(this::dispatch);
        return claimed.isPresent();
    }

    /** Runs one claimed job and records the outcome. Never throws. */
    private void dispatch(PostureJob job) {
        Endpoint endpoint = job.getEndpoint();

        try {
            RunResult result = switch (job.getJobType()) {
                case POSTURE_CHECK -> runPostureAgent(job, endpoint);
                case HARDWARE_CHECK -> runHardwareAgent(job, endpoint);
            };
            handleResult(job, endpoint, result);
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
                    + ", timedOut=" + result.timedOut() + "). Last output: "
                    + truncate(result.rawOutput()));
            return;
        }

        if (!json.path("submitted").asBoolean(false)) {
            String detail = json.path("detail").asText(null);
            String submitError = json.path("submitError").asText(null);
            fail(job, endpoint, detail != null ? detail : "Submission failed: " + submitError);
            return;
        }

        jobService.markComplete(job.getId());
    }

    /** Writes permanent failure evidence, then fails the job (retry or FAILED). */
    private void fail(PostureJob job, Endpoint endpoint, String reason) {
        switch (job.getJobType()) {
            case POSTURE_CHECK -> assessmentService.recordFailure(endpoint.getId(), job.getId(), reason);
            case HARDWARE_CHECK -> hardwareHealthService.recordFailure(endpoint.getId(), job.getId(), reason);
        }
        jobService.markFailed(job.getId(), reason);
    }

    private RunResult runPostureAgent(PostureJob job, Endpoint endpoint) throws IOException, InterruptedException {
        List<String> command = List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", resolveScript(postureProps.getScriptPath()),
                "-ComputerName", targetFor(endpoint),
                "-JobId", job.getId().toString(),
                "-PostureServer", postureProps.getServerUrl(),
                "-CimOperationTimeoutSec", String.valueOf(postureProps.getCimTimeoutSeconds()),
                "-PostureServerTimeoutSec", String.valueOf(postureProps.getServerTimeoutSeconds())
        );
        Map<String, String> env = isBlank(postureProps.getApiKey())
                ? Map.of()
                : Map.of(API_KEY_ENV_VAR, postureProps.getApiKey());
        return runProcess(command, postureProps.getProcessTimeoutSeconds(), env);
    }

    private RunResult runHardwareAgent(PostureJob job, Endpoint endpoint) throws IOException, InterruptedException {
        List<String> command = List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", resolveScript(hardwareProps.getScriptPath()),
                "-ComputerName", targetFor(endpoint),
                "-JobId", job.getId().toString(),
                "-PostureAppBase", hardwareProps.getServerBaseUrl(),
                "-CimOperationTimeoutSec", String.valueOf(hardwareProps.getCimTimeoutSeconds()),
                "-SubmitTimeoutSec", String.valueOf(hardwareProps.getSubmitTimeoutSeconds())
        );
        Map<String, String> env = isBlank(postureProps.getApiKey())
                ? Map.of()
                : Map.of(API_KEY_ENV_VAR, postureProps.getApiKey());
        return runProcess(command, hardwareProps.getProcessTimeoutSeconds(), env);
    }

    private String resolveScript(String configured) throws IOException {
        if (isBlank(configured)) {
            throw new IOException("Agent script path is not configured (app.posture.script-path / app.hardware.script-path)");
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Agent script not found: " + path);
        }
        return path.toString();
    }

    private String targetFor(Endpoint endpoint) {
        if (!isBlank(endpoint.getIpAddress())) return endpoint.getIpAddress();
        if (!isBlank(endpoint.getHostname())) return endpoint.getHostname();
        throw new IllegalStateException("Endpoint " + endpoint.getMacAddress() + " has neither an IP address nor a hostname");
    }

    /**
     * Runs a command with an outer timeout and captures stdout+stderr. The process
     * is killed on timeout, and also if this thread is interrupted (shutdown).
     */
    private RunResult runProcess(List<String> command, int timeoutSeconds, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process process = pb.start();

        StringBuffer output = new StringBuffer();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // stream closed because the process ended or was killed
            }
        });
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly(); // do not leave an orphan powershell.exe on shutdown
            throw e;
        }
        if (!finished) {
            process.destroyForcibly();
        }
        drain.join(2000);

        String text = output.toString();
        return new RunResult(finished ? process.exitValue() : -1, !finished, text, extractResultJson(text));
    }

    private JsonNode extractResultJson(String output) {
        JsonNode found = null;
        for (String line : output.split("\n")) {
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

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record RunResult(int exitCode, boolean timedOut, String rawOutput, JsonNode resultJson) {}
}