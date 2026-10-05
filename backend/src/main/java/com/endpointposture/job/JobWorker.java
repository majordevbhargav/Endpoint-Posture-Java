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
 * Turns claimed jobs into agent runs. {@link JobWorkerPool} calls
 * {@link #runOnce()} from several threads.
 *
 * <h2>How one job flows</h2>
 * <ol>
 *   <li>Claim the next eligible job ({@code RUNNING}).</li>
 *   <li>Build the agent command (posture jobs also get the active application policy)
 *       and hand it to the {@link AgentRunner}, which launches it and captures output.</li>
 *   <li>The agent POSTs its own report and prints one {@code RESULT_JSON:} line.</li>
 *   <li>{@code submitted: true} means COMPLETE.</li>
 *   <li>Anything else writes a failure evidence row and fails the job.</li>
 * </ol>
 *
 * <p>This class decides <em>what</em> to run and how to read the result; the
 * {@link AgentRunner} decides <em>where and how</em> it runs. Nothing here calls
 * Cisco ISE; dispatch is observation only.</p>
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private static final String RESULT_PREFIX = "RESULT_JSON:";
    private static final String API_KEY_ENV_VAR = "POSTURE_API_KEY";
    private static final String POLICY_LIST_DELIMITER = "|";

    private final JobService jobService;
    private final AssessmentService assessmentService;
    private final HardwareHealthService hardwareHealthService;
    private final DiagnosticService diagnosticService;
    private final SecurityIndicatorService securityIndicatorService;
    private final PostureAgentProperties postureProps;
    private final HardwareAgentProperties hardwareProps;
    private final DiagnosticAgentProperties diagnosticProps;
    private final SecurityIndicatorAgentProperties securityProps;
    private final PolicyService policyService;
    private final AgentRunner agentRunner;
    private final ObjectMapper objectMapper;

    public JobWorker(JobService jobService,
                     AssessmentService assessmentService,
                     HardwareHealthService hardwareHealthService,
                     DiagnosticService diagnosticService,
                     SecurityIndicatorService securityIndicatorService,
                     PostureAgentProperties postureProps,
                     HardwareAgentProperties hardwareProps,
                     DiagnosticAgentProperties diagnosticProps,
                     SecurityIndicatorAgentProperties securityProps,
                     PolicyService policyService,
                     AgentRunner agentRunner,
                     ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.assessmentService = assessmentService;
        this.hardwareHealthService = hardwareHealthService;
        this.diagnosticService = diagnosticService;
        this.securityIndicatorService = securityIndicatorService;
        this.postureProps = postureProps;
        this.hardwareProps = hardwareProps;
        this.diagnosticProps = diagnosticProps;
        this.securityProps = securityProps;
        this.policyService = policyService;
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
            RunResult result = switch (job.getJobType()) {
                case POSTURE_CHECK -> runPostureAgent(job, endpoint);
                case HARDWARE_CHECK -> runHardwareAgent(job, endpoint);
                case DIAGNOSTIC_CHECK -> runDiagnosticAgent(job, endpoint);
                case SECURITY_CHECK -> runSecurityAgent(job, endpoint);
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

    private void fail(PostureJob job, Endpoint endpoint, String reason) {
        switch (job.getJobType()) {
            case POSTURE_CHECK -> assessmentService.recordFailure(endpoint.getId(), job.getId(), reason);
            case HARDWARE_CHECK -> hardwareHealthService.recordFailure(endpoint.getId(), job.getId(), reason);
            case DIAGNOSTIC_CHECK -> diagnosticService.recordFailure(endpoint.getId(), job.getId(), reason);
            case SECURITY_CHECK -> securityIndicatorService.recordFailure(endpoint.getId(), job.getId(), reason);
        }
        jobService.markFailed(job.getId(), reason);
    }

    private RunResult runPostureAgent(PostureJob job, Endpoint endpoint) throws IOException, InterruptedException {
        PolicyService.PolicySnapshot policy = policyService.getActive();

        List<String> command = new ArrayList<>(List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", resolveScript(postureProps.getScriptPath()),
                "-ComputerName", targetFor(endpoint),
                "-JobId", job.getId().toString(),
                "-PostureServer", postureProps.getServerUrl(),
                "-CimOperationTimeoutSec", String.valueOf(postureProps.getCimTimeoutSeconds()),
                "-PostureServerTimeoutSec", String.valueOf(postureProps.getServerTimeoutSeconds()),
                "-PolicyVersion", String.valueOf(policy.version())
        ));

        if (!policy.requiredApps().isEmpty()) {
            command.add("-RequiredAppsList");
            command.add(String.join(POLICY_LIST_DELIMITER, policy.requiredApps()));
        }
        if (!policy.blockedApps().isEmpty()) {
            command.add("-BlockedAppsList");
            command.add(String.join(POLICY_LIST_DELIMITER, policy.blockedApps()));
        }

        return execute(command, postureProps.getProcessTimeoutSeconds());
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
        return execute(command, hardwareProps.getProcessTimeoutSeconds());
    }

    private RunResult runDiagnosticAgent(PostureJob job, Endpoint endpoint) throws IOException, InterruptedException {
        List<String> command = List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", resolveScript(diagnosticProps.getScriptPath()),
                "-ComputerName", targetFor(endpoint),
                "-Mac", endpoint.getMacAddress(),
                "-JobId", job.getId().toString(),
                "-PostureAppBase", diagnosticProps.getServerBaseUrl(),
                "-WinRmOpenTimeoutSec", String.valueOf(diagnosticProps.getWinrmOpenTimeoutSeconds()),
                "-WinRmOperationTimeoutSec", String.valueOf(diagnosticProps.getWinrmOperationTimeoutSeconds()),
                "-SubmitTimeoutSec", String.valueOf(diagnosticProps.getSubmitTimeoutSeconds()),
                "-DnsTestName", diagnosticProps.getDnsTestName(),
                "-InternetTarget", diagnosticProps.getInternetTarget()
        );
        return execute(command, diagnosticProps.getProcessTimeoutSeconds());
    }

    private RunResult runSecurityAgent(PostureJob job, Endpoint endpoint) throws IOException, InterruptedException {
        List<String> command = List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", resolveScript(securityProps.getScriptPath()),
                "-ComputerName", targetFor(endpoint),
                "-Mac", endpoint.getMacAddress(),
                "-JobId", job.getId().toString(),
                "-PostureAppBase", securityProps.getServerBaseUrl(),
                "-WinRmOpenTimeoutSec", String.valueOf(securityProps.getWinrmOpenTimeoutSeconds()),
                "-WinRmOperationTimeoutSec", String.valueOf(securityProps.getWinrmOperationTimeoutSeconds()),
                "-SubmitTimeoutSec", String.valueOf(securityProps.getSubmitTimeoutSeconds()),
                "-SampleCount", String.valueOf(securityProps.getSampleCount()),
                "-SampleIntervalSec", String.valueOf(securityProps.getSampleIntervalSeconds())
        );
        return execute(command, securityProps.getProcessTimeoutSeconds());
    }

    /** Hands the command to the runner and parses the {@code RESULT_JSON:} line from its output. */
    private RunResult execute(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        AgentRunResult raw = agentRunner.run(new AgentCommand(command, timeoutSeconds, agentEnv()));
        return new RunResult(raw.exitCode(), raw.timedOut(), raw.rawOutput(), extractResultJson(raw.rawOutput()));
    }

    private Map<String, String> agentEnv() {
        return isBlank(postureProps.getApiKey())
                ? Map.of()
                : Map.of(API_KEY_ENV_VAR, postureProps.getApiKey());
    }

    private String resolveScript(String configured) throws IOException {
        if (isBlank(configured)) {
            throw new IOException("Agent script path is not configured");
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
        throw new IllegalStateException(
                "Endpoint " + endpoint.getMacAddress()
                        + " has neither an IP address nor a hostname");
    }

    /**
     * Finds the last {@code RESULT_JSON:} line in the agent output.
     *
     * @return the parsed JSON, or {@code null} if there is no such line or the last one is malformed
     *         (so the job fails rather than passes)
     */
    JsonNode extractResultJson(String output) {
        JsonNode found = null;
        for (String line : output.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(RESULT_PREFIX)) {
                try {
                    found = objectMapper.readTree(
                            trimmed.substring(RESULT_PREFIX.length()));
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

    private record RunResult(
            int exitCode,
            boolean timedOut,
            String rawOutput,
            JsonNode resultJson) {}
}