package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Executes only allowlisted scripts with argv boundaries; never evaluates caller-supplied PowerShell text. */
public final class PowerShellExecutor {
    public record ExecutionResult(int exitCode, JsonNode result, boolean stdoutTruncated, boolean stderrTruncated) {}
    private record Captured(String text, boolean truncated) {}

    private final PowerShellExecutionConfig config;

    public PowerShellExecutor(PowerShellExecutionConfig config) { this.config = config; }

    public ExecutionResult execute(ClaimedJob claimed, ValidatedJob job) throws Exception {
        Path script = resolveApprovedScript(job.scriptName());
        Instant deadline = claimed.leaseExpiresAt().minus(config.leaseSafetyMargin());
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) throw new IllegalStateException("Job lease safety deadline has passed");
        Duration timeout = remaining.compareTo(job.processTimeout()) < 0 ? remaining : job.processTimeout();

        List<String> command = new ArrayList<>();
        command.add(config.executable());
        command.add("-NoLogo");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-ExecutionPolicy");
        command.add("RemoteSigned");
        command.add("-File");
        command.add(script.toString());
        command.addAll(job.arguments());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(config.scriptsDirectory().toFile());
        // The legacy scripts use POSTURE_API_KEY / X-Posture-Api-Key. Never forward that
        // shared backend secret (or any similarly named secret) from the worker process.
        builder.environment().keySet().removeIf(k -> k.toUpperCase(java.util.Locale.ROOT).matches(".*(PASSWORD|TOKEN|SECRET|API[_-]?KEY|CREDENTIAL).*"));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Could not start configured PowerShell executable", e);
        }

        try (var readers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Captured> stdout = CompletableFuture.supplyAsync(
                    () -> capture(process.getInputStream(), config.maxOutputBytes()), readers);
            CompletableFuture<Captured> stderr = CompletableFuture.supplyAsync(
                    () -> capture(process.getErrorStream(), config.maxOutputBytes()), readers);
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                terminateTree(process);
                throw new java.util.concurrent.TimeoutException("PowerShell job exceeded its execution/lease deadline");
            }
            Captured out = stdout.get(5, TimeUnit.SECONDS);
            Captured err = stderr.get(5, TimeUnit.SECONDS);
            // A valid partial report is retained even when collection exits non-zero.
            JsonNode result = parseResult(out.text(), job.jobId(), job.jobType());
            return new ExecutionResult(process.exitValue(), result, out.truncated(), err.truncated());
        } catch (InterruptedException e) {
            terminateTree(process);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) terminateTree(process);
        }
    }

    Path resolveApprovedScript(String name) throws IOException {
        if (!List.of("posture_agent.ps1", "hardware_health_agent.ps1", "diagnostic_agent.ps1",
                "security_indicator_agent.ps1").contains(name)) {
            throw new IllegalArgumentException("Script is not in the worker allowlist");
        }
        Path root = config.scriptsDirectory().toRealPath();
        Path candidate = root.resolve(name).normalize();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate) || !candidate.toRealPath().startsWith(root))
            throw new IllegalArgumentException("Approved script is missing or resolves outside the configured scripts directory");
        return candidate.toRealPath();
    }

    private JsonNode parseResult(String stdout, java.util.UUID expectedJobId, String expectedJobType) throws IOException {
        String found = null;
        for (String line : stdout.split("\\R")) {
            if (line.startsWith("RESULT_JSON:")) found = line.substring("RESULT_JSON:".length()).trim();
        }
        if (found == null || found.isBlank()) throw new IOException("PowerShell output did not contain RESULT_JSON");
        JsonNode node = new ObjectMapper().readTree(found);
        if (node == null || !node.isObject()) throw new IOException("RESULT_JSON must be a JSON object");
        JsonNode id = node.get("jobId");
        if (id == null || !id.isTextual() || !expectedJobId.toString().equalsIgnoreCase(id.asText()))
            throw new IOException("RESULT_JSON jobId does not match the claimed job");
        JsonNode version=node.get("schemaVersion"), type=node.get("jobType"), outcome=node.get("collectionOutcome");
        JsonNode payload=node.get("reportPayload"), errors=node.get("errors"), warnings=node.get("warnings");
        if (version==null || !version.isInt() || version.intValue()!=1
                || type==null || !type.isTextual() || !expectedJobType.equals(type.asText())
                || outcome==null || !outcome.isTextual()
                || !List.of("SUCCESS","PARTIAL","FAILED").contains(outcome.asText())
                || payload==null || (!payload.isObject() && !(payload.isNull() && "FAILED".equals(outcome.asText()))) || errors==null || !errors.isArray()
                || warnings==null || !warnings.isArray())
            throw new IOException("RESULT_JSON does not match worker output schema version 1");
        return node;
    }

    private static Captured capture(InputStream input, int limit) {
        try (input; ByteArrayOutputStream kept = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            boolean truncated = false;
            while ((count = input.read(buffer)) != -1) {
                int room = limit - kept.size();
                if (room > 0) kept.write(buffer, 0, Math.min(room, count));
                if (count > room) truncated = true;
            }
            return new Captured(kept.toString(StandardCharsets.UTF_8), truncated);
        } catch (IOException e) {
            return new Captured("", true);
        }
    }

    private static void terminateTree(Process process) {
        ProcessHandle root = process.toHandle();
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();

        // Keep handles, not just PIDs: after the parent exits, descendants() may no longer
        // expose children that are still running. Snapshot before and during graceful shutdown.
        collectDescendants(root, descendants);
        destroyGracefully(descendants.values());
        waitForHandles(descendants.values(), 500);

        // A child may have spawned another process while the first snapshot was being stopped.
        // Capture those descendants while the parent is still available.
        collectDescendants(root, descendants);
        process.destroy();

        try {
            process.waitFor(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            forceTerminate(descendants.values());
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return;
        }

        // Always force any retained descendant that survived the grace period, regardless
        // of whether the parent exited. Do not make child cleanup conditional on parent state.
        collectDescendants(root, descendants);
        forceTerminate(descendants.values());
        process.destroyForcibly();

        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        waitForHandles(descendants.values(), 2_000);
    }

    private static void collectDescendants(ProcessHandle root, Map<Long, ProcessHandle> descendants) {
        try {
            root.descendants().forEach(handle -> descendants.putIfAbsent(handle.pid(), handle));
        } catch (RuntimeException ignored) {
            // Retained handles are still cleaned up even if a later snapshot is unavailable.
        }
    }

    private static void destroyGracefully(Iterable<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            try {
                if (handle.isAlive()) handle.destroy();
            } catch (RuntimeException ignored) {
                // Continue cleanup for the remaining descendants.
            }
        }
    }

    private static void forceTerminate(Iterable<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            try {
                if (handle.isAlive()) handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // Continue cleanup for the remaining descendants.
            }
        }
    }

    private static void waitForHandles(Iterable<ProcessHandle> handles, long timeoutMillis) {
        List<CompletableFuture<ProcessHandle>> exits = new ArrayList<>();
        for (ProcessHandle handle : handles) {
            try {
                if (handle.isAlive()) exits.add(handle.onExit());
            } catch (RuntimeException ignored) {
                // A process may have exited between isAlive() and onExit().
            }
        }
        if (exits.isEmpty()) return;
        try {
            CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new))
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Cleanup is best-effort after force termination; never hide the original job failure.
        }
    }
}