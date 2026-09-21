package com.endpointposture.job;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.config.PostureAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
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
 * Background worker that turns queued jobs into real PowerShell runs.
 *
 * <p>The Spring equivalent of the Python project's {@code auto_worker()}
 * thread, minus the flat-file locking - claiming happens safely inside
 * Postgres via {@link PostureJobRepository#findNextClaimable()}.</p>
 *
 * <h2>How one job flows</h2>
 * <ol>
 *   <li>Every poll tick, claim the next eligible job ({@code RUNNING}).</li>
 *   <li>Start the matching PowerShell agent as a child process, with an
 *       outer timeout, and capture its output.</li>
 *   <li>The agent collects data and POSTs it to the backend itself, then
 *       prints one {@code RESULT_JSON:} line describing what happened.</li>
 *   <li>If that line says the submission worked, the job is {@code COMPLETE}
 *       (the real assessment was already saved by the ingestion endpoint).</li>
 *   <li>Otherwise - timeout, crash, no {@code RESULT_JSON}, or a failed
 *       submission - a posture job writes an {@code ERROR} assessment row
 *       (a failed attempt is still evidence) and the job is failed, which
 *       retries with backoff while attempts remain.</li>
 * </ol>
 *
 * <p>Because a posture job can be attempted several times, one job may leave
 * several {@code ERROR} assessments before it finally succeeds or gives up.</p>
 *
 * <p>Known gap: if the whole backend dies while a job is {@code RUNNING},
 * that job stays {@code RUNNING} after restart. A recovery sweep for stale
 * running jobs is a later addition.</p>
 *
 * <p>Nothing here calls Cisco ISE; dispatch is observation only.</p>
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    /** Prefix of the machine-readable line every agent prints on stdout. */
    private static final String RESULT_PREFIX = "RESULT_JSON:";

    /** Environment variable the posture agent reads its API key from. */
    private static final String API_KEY_ENV_VAR = "POSTURE_API_KEY";

    private final JobService jobService;
    private final AssessmentService assessmentService;
    private final PostureAgentProperties postureProps;
    private final HardwareAgentProperties hardwareProps;
    private final ObjectMapper objectMapper;

    public JobWorker(JobService jobService,
                     AssessmentService assessmentService,
                     PostureAgentProperties postureProps,
                     HardwareAgentProperties hardwareProps,
                     ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.assessmentService = assessmentService;
        this.postureProps = postureProps;
        this.hardwareProps = hardwareProps;
        this.objectMapper = objectMapper;
    }

    /**
     * Poll tick: claims at most one job and runs it to completion.
     * {@code fixedDelay} means the next tick starts only after this one
     * finishes, so a single worker never overlaps itself.
     */
    @Scheduled(fixedDelayString = "${app.jobs.poll-interval-ms:3000}")
    public void pollAndRun() {
        jobService.claimNextJob().ifPresent(this::dispatch);
    }

    /** Runs one claimed job and records the outcome. Never throws. */
    private void dispatch(PostureJob job) {
        // Already loaded: JobService.claimNextJob() resolves the lazy proxy
        // inside its transaction, so reading it here is safe.
        Endpoint endpoint = job.getEndpoint();

        try {
            RunResult result = switch (job.getJobType()) {
                case POSTURE_CHECK -> runPostureAgent(job, endpoint);
                case HARDWARE_CHECK -> runHardwareAgent(job, endpoint);
            };
            handleResult(job, endpoint, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the interrupt for the scheduler
            fail(job, endpoint, "Dispatch interrupted");
        } catch (Exception e) {
            log.error("Dispatch failed for job {}", job.getId(), e);
            fail(job, endpoint, "Dispatch error: " + e.getMessage());
        }
    }

    /**
     * Decides success or failure from what the agent printed.
     * Success requires a {@code RESULT_JSON} line with {@code submitted: true}.
     */
    private void handleResult(PostureJob job, Endpoint endpoint, RunResult result) {
        JsonNode json = result.resultJson();

        if (json == null) {
            // No RESULT_JSON at all - killed for exceeding the outer timeout,
            // or crashed before it could write anything. Exactly the "hung
            // but listening WinRM service" case the explicit-timeout rule
            // exists for: still worth a permanent record that this was tried.
            fail(job, endpoint, "No RESULT_JSON (exit=" + result.exitCode()
                    + ", timedOut=" + result.timedOut() + "). Last output: "
                    + truncate(result.rawOutput()));
            return;
        }

        if (!json.path("submitted").asBoolean(false)) {
            // The agent ran but could not deliver its report (collection
            // error, or the HTTP POST back to this backend failed).
            String detail = json.path("detail").asText(null);
            String submitError = json.path("submitError").asText(null);
            fail(job, endpoint, detail != null ? detail : "Submission failed: " + submitError);
            return;
        }

        // submitted=true means the ingestion endpoint already wrote the real
        // assessment over HTTP - nothing more to persist here.
        jobService.markComplete(job.getId());
    }

    /**
     * Records a failed attempt. Posture jobs also get an {@code ERROR}
     * assessment row so the attempt leaves permanent evidence; the job
     * itself is failed (retry with backoff, or {@code FAILED} when out of attempts).
     */
    private void fail(PostureJob job, Endpoint endpoint, String reason) {
        if (job.getJobType() == JobType.POSTURE_CHECK) {
            assessmentService.recordFailure(endpoint.getId(), job.getId(), reason);
        }
        jobService.markFailed(job.getId(), reason);
    }

    /** Builds and runs the posture agent command. The API key travels in the environment, not the command line. */
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

    /** Builds and runs the hardware-health agent command. */
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
        return runProcess(command, hardwareProps.getProcessTimeoutSeconds(), Map.of());
    }

    /**
     * Turns the configured script path into an absolute path and checks it
     * exists, so a wrong {@code script-path} produces a clear error message
     * instead of an opaque PowerShell failure.
     */
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

    /** The address the agent connects to: the endpoint's IP, or its hostname if no IP is known. */
    private String targetFor(Endpoint endpoint) {
        if (!isBlank(endpoint.getIpAddress())) return endpoint.getIpAddress();
        if (!isBlank(endpoint.getHostname())) return endpoint.getHostname();
        throw new IllegalStateException("Endpoint " + endpoint.getMacAddress() + " has neither an IP address nor a hostname");
    }

    /**
     * Runs a command with an outer timeout and captures stdout+stderr.
     * If the timeout passes, the process is killed - this is the backstop
     * behind the agents' own (shorter) per-operation timeouts.
     */
    private RunResult runProcess(List<String> command, int timeoutSeconds, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process process = pb.start();

        // StringBuffer (synchronized), not StringBuilder: the drain thread
        // writes while this thread may read after a bounded join below.
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
        // Drained on a separate thread so the child's stdout buffer never
        // fills and blocks it while we're waiting below.
        drain.setDaemon(true);
        drain.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        drain.join(2000);

        String text = output.toString();
        return new RunResult(finished ? process.exitValue() : -1, !finished, text, extractResultJson(text));
    }

    /**
     * Finds the last {@code RESULT_JSON:} line in the agent's output and parses it.
     *
     * @return the parsed JSON, or {@code null} if there is no such line or it is malformed
     */
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

    /**
     * Outcome of one agent run.
     *
     * @param exitCode   process exit code, or -1 if it was killed for timing out
     * @param timedOut   whether the outer timeout fired
     * @param rawOutput  everything the process printed
     * @param resultJson parsed {@code RESULT_JSON} line, or {@code null} if absent/unparseable
     */
    private record RunResult(int exitCode, boolean timedOut, String rawOutput, JsonNode resultJson) {}
}
