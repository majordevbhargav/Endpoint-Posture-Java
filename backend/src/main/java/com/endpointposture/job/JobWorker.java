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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 *   <li>Start the matching PowerShell agent with an outer timeout, capture output.
 *       Posture jobs are handed the active application policy.</li>
 *   <li>The agent POSTs its own report and prints one {@code RESULT_JSON:} line.</li>
 *   <li>{@code submitted: true} means COMPLETE.</li>
 *   <li>Anything else writes a failure evidence row and fails the job.</li>
 * </ol>
 *
 * <p>Nothing here calls Cisco ISE; dispatch is observation only.</p>
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
        this.objectMapper = objectMapper;
    }

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

        return runProcess(command, postureProps.getProcessTimeoutSeconds(), agentEnv());
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
        return runProcess(command, hardwareProps.getProcessTimeoutSeconds(), agentEnv());
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
        return runProcess(command, diagnosticProps.getProcessTimeoutSeconds(), agentEnv());
    }

    private RunResult runSecurityAgent(PostureJob job, Endpoint endpoint)
            throws IOException, InterruptedException {
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
        return runProcess(command, securityProps.getProcessTimeoutSeconds(), agentEnv());
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

    private RunResult runProcess(List<String> command, int timeoutSeconds, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process process = pb.start();

        StringBuffer output = new StringBuffer();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }

        if (!finished) {
            process.destroyForcibly();
        }

        drain.join(2000);

        String text = output.toString();
        return new RunResult(
                finished ? process.exitValue() : -1,
                !finished,
                text,
                extractResultJson(text)
        );
    }

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
