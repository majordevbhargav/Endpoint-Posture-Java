package com.endpointposture.sim;

import com.endpointposture.job.AgentCommand;
import com.endpointposture.job.AgentRunResult;
import com.endpointposture.job.AgentRunner;
import com.endpointposture.posture.AssessmentStatus;
import com.endpointposture.posture.PostureIngestService;
import com.endpointposture.posture.dto.CheckInput;
import com.endpointposture.posture.dto.PostureReportRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stands in for PowerShell in the {@code sim} profile. Sleeps about
 * {@code app.sim.job-seconds} (plus or minus 30 percent), occasionally fails
 * ({@code app.sim.failure-percent}), and for posture jobs writes a realistic synthetic
 * report through the real {@link PostureIngestService}, so database growth and write
 * cost are real. Other job types only sleep.
 *
 * <p>The simulated device is identified from the {@code -ComputerName} argument, whose
 * IP maps back to a device number ({@link SimIdentity}).</p>
 */
@Component
@Primary
@Profile("sim")
public class SimulatedAgentRunner implements AgentRunner {

    private final PostureIngestService ingest;
    private final int jobSeconds;
    private final int failurePercent;

    public SimulatedAgentRunner(PostureIngestService ingest,
                                @Value("${app.sim.job-seconds:20}") int jobSeconds,
                                @Value("${app.sim.failure-percent:2}") int failurePercent) {
        this.ingest = ingest;
        this.jobSeconds = jobSeconds;
        this.failurePercent = failurePercent;
    }

    @Override
    public AgentRunResult run(AgentCommand command) throws InterruptedException {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long sleepMs = (long) (jobSeconds * 1000 * (0.7 + rnd.nextDouble() * 0.6));
        Thread.sleep(sleepMs);

        if (rnd.nextInt(100) < failurePercent) {
            return new AgentRunResult(1, false,
                    "RESULT_JSON:{\"submitted\":false,\"detail\":\"simulated WinRM timeout\"}\n");
        }

        List<String> args = command.command();
        boolean posture = args.stream().anyMatch(a -> a.endsWith("posture_agent.ps1"));
        if (posture) {
            int i = SimIdentity.indexFromIp(args.get(args.indexOf("-ComputerName") + 1));
            ingest.ingest(syntheticReport(i, args.contains("-JobId") ? args.get(args.indexOf("-JobId") + 1) : null, rnd));
        }
        return new AgentRunResult(0, false, "RESULT_JSON:{\"submitted\":true}\n");
    }

    private PostureReportRequest syntheticReport(int i, String jobId, ThreadLocalRandom rnd) {
        int roll = rnd.nextInt(100);
        AssessmentStatus status = roll < 80 ? AssessmentStatus.COMPLIANT
                : roll < 95 ? AssessmentStatus.NON_COMPLIANT : AssessmentStatus.ERROR;

        List<CheckInput> checks = List.of(
                new CheckInput("FIREWALL", status == AssessmentStatus.NON_COMPLIANT
                        ? AssessmentStatus.NON_COMPLIANT : AssessmentStatus.COMPLIANT, Map.of("summary", "sim")),
                new CheckInput("OPEN_PORTS", AssessmentStatus.COMPLIANT, Map.of("summary", "sim")),
                new CheckInput("APPLICATIONS", AssessmentStatus.COMPLIANT, Map.of("summary", "sim")));

        // Sized like a real laptop (about 60 apps, 15 ports, 10 processes) to measure storage honestly.
        List<Map<String, Object>> apps = new ArrayList<>();
        for (int a = 0; a < 60; a++) {
            apps.add(Map.of("name", "Simulated Application " + a, "version", "1." + a, "publisher", "SimCorp"));
        }
        List<Map<String, Object>> ports = new ArrayList<>();
        for (int p = 0; p < 15; p++) {
            ports.add(Map.of("port", 1000 + p, "process", "svchost.exe", "pid", 100 + p, "reachable", p % 3 == 0));
        }
        List<Map<String, Object>> procs = new ArrayList<>();
        for (int p = 0; p < 10; p++) {
            procs.add(Map.of("name", "proc" + p + ".exe", "pid", 200 + p, "memory_mb", 100.0 + p));
        }

        return new PostureReportRequest(
                jobId, SimIdentity.mac(i), "SIM-" + String.format("%05d", i),
                "Microsoft Windows 11 Pro", "10.0.22631", SimIdentity.ip(i), List.of(SimIdentity.ip(i)),
                Instant.now(), status, null, checks,
                new PostureReportRequest.InventoryDto(ports, apps, procs,
                        Map.of("cpu_percent", 12.5, "memory_percent", 48.0)));
    }
}