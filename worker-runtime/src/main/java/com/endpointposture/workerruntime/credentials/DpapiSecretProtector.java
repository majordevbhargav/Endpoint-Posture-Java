package com.endpointposture.workerruntime.credentials;

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
 * stdin, never the command line. This avoids PowerShell module auto-loading and uses the native
 * Windows DPAPI implementation. Only the Windows account that protected a value can unprotect it,
 * so the worker service account must be the account that enrolled.
 */
public final class DpapiSecretProtector implements SecretProtector {
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
        Process p;
        try {
            p = new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script).start();
        } catch (IOException e) {
            throw new SecretProtectionException("Cannot start PowerShell (" + powershell + "): " + e.getMessage(), e);
        }
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
        try {
            try (OutputStream os = p.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new SecretProtectionException("PowerShell did not finish within " + timeout.toSeconds() + " s");
            }
            if (p.exitValue() != 0) {
                String e = err.join().trim();
                throw new SecretProtectionException("PowerShell failed (exit " + p.exitValue() + ")"
                        + (e.isEmpty() ? "" : ": " + (e.length() > 300 ? e.substring(0, 300) : e)));
            }
            return out.join().trim();
        } catch (IOException e) {
            p.destroyForcibly();
            throw new SecretProtectionException("Cannot talk to PowerShell: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SecretProtectionException("Interrupted while waiting for PowerShell", e);
        }
    }

    private static String read(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}