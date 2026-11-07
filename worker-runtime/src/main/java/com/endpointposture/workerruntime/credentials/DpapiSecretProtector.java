package com.endpointposture.workerruntime.credentials;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Windows DPAPI (current-user scope) through .NET ProtectedData. The secret goes in through
 * stdin, never the command line. Only the Windows account that protected a value can unprotect it,
 * so the worker service account must be the account that enrolled.
 */
public final class DpapiSecretProtector implements SecretProtector {
    private static final int MAX_INPUT_BYTES = 16 * 1024;
    private static final int MAX_STDOUT_BYTES = 64 * 1024;
    private static final int MAX_STDERR_BYTES = 16 * 1024;

    private static final String PROTECT =
            "$ErrorActionPreference='Stop';"
            + "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd();"
            + "$bytes=[Text.Encoding]::UTF8.GetBytes($in);"
            + "$protected=[Security.Cryptography.ProtectedData]::Protect($bytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "[Console]::Out.Write([Convert]::ToBase64String($protected))";
    private static final String UNPROTECT =
            "$ErrorActionPreference='Stop';"
            + "Add-Type -AssemblyName System.Security;"
            + "$in=[Console]::In.ReadToEnd().Trim();"
            + "$bytes=[Convert]::FromBase64String($in);"
            + "$plain=[Security.Cryptography.ProtectedData]::Unprotect($bytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($plain))";

    private record Captured(String value, boolean truncated) {}

    private final String powershell;
    private final Duration timeout;

    public DpapiSecretProtector() {
        this("powershell.exe", Duration.ofSeconds(30));
    }

    public DpapiSecretProtector(String powershell, Duration timeout) {
        this.powershell = powershell;
        this.timeout = timeout;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    public String protect(String plaintext) {
        requireWindows();
        if (plaintext == null || plaintext.isBlank()) throw new SecretProtectionException("Nothing to protect");
        String out = run(PROTECT, plaintext);
        if (out.isEmpty()) throw new SecretProtectionException("DPAPI returned an empty value");
        return out;
    }

    @Override
    public String unprotect(String protectedValue) {
        requireWindows();
        if (protectedValue == null || protectedValue.isBlank())
            throw new SecretProtectionException("Nothing to unprotect");
        String out = run(UNPROTECT, protectedValue);
        if (out.isEmpty()) throw new SecretProtectionException("DPAPI returned an empty value");
        return out;
    }

    private static void requireWindows() {
        if (!isWindows()) throw new SecretProtectionException("DPAPI is only available on Windows");
    }

    private String run(String script, String stdin) {
        byte[] input = stdin.getBytes(StandardCharsets.UTF_8);
        if (input.length > MAX_INPUT_BYTES)
            throw new SecretProtectionException("DPAPI input exceeds the maximum allowed size");

        Process p;
        try {
            p = new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script).start();
        } catch (IOException e) {
            throw new SecretProtectionException("Cannot start PowerShell (" + powershell + "): " + e.getMessage(), e);
        }

        // Both streams are drained concurrently to avoid blocking the child process. Captured
        // memory is bounded; excess output is discarded and reported after process completion.
        CompletableFuture<Captured> out = CompletableFuture.supplyAsync(
                () -> readBounded(p.getInputStream(), MAX_STDOUT_BYTES));
        CompletableFuture<Captured> err = CompletableFuture.supplyAsync(
                () -> readBounded(p.getErrorStream(), MAX_STDERR_BYTES));
        try {
            try (OutputStream os = p.getOutputStream()) {
                os.write(input);
            }
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new SecretProtectionException("PowerShell did not finish within " + timeout.toSeconds() + " s");
            }

            Captured stdout = out.join();
            Captured stderr = err.join();
            if (stdout.truncated())
                throw new SecretProtectionException("PowerShell output exceeded the maximum allowed size");
            if (p.exitValue() != 0) {
                String detail = stderr.value().trim();
                if (stderr.truncated()) detail += " (diagnostic output truncated)";
                throw new SecretProtectionException("PowerShell failed (exit " + p.exitValue() + ")"
                        + (detail.isEmpty() ? "" : ": " + (detail.length() > 300 ? detail.substring(0, 300) : detail)));
            }
            return stdout.value().trim();
        } catch (IOException e) {
            p.destroyForcibly();
            throw new SecretProtectionException("Cannot talk to PowerShell: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SecretProtectionException("Interrupted while waiting for PowerShell", e);
        }
    }

    private static Captured readBounded(InputStream in, int limit) {
        try (in; ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(limit, 8192))) {
            byte[] buffer = new byte[4096];
            boolean truncated = false;
            int count;
            while ((count = in.read(buffer)) != -1) {
                int remaining = limit - captured.size();
                if (remaining > 0) captured.write(buffer, 0, Math.min(remaining, count));
                if (count > remaining) truncated = true;
            }
            return new Captured(captured.toString(StandardCharsets.UTF_8), truncated);
        } catch (IOException e) {
            throw new SecretProtectionException("Cannot read PowerShell output", e);
        }
    }
}
