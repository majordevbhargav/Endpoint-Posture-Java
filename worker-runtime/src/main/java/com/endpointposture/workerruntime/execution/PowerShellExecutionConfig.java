package com.endpointposture.workerruntime.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Local execution settings. Execution is deliberately opt-in until script output contracts are audited. */
public record PowerShellExecutionConfig(boolean enabled, String executable, Path scriptsDirectory,
                                        Duration leaseSafetyMargin, int maxOutputBytes) {
    public PowerShellExecutionConfig {
        if (executable == null || executable.isBlank()) throw new IllegalArgumentException("PowerShell executable is required");
        if (scriptsDirectory == null) throw new IllegalArgumentException("Script directory is required");
        if (leaseSafetyMargin == null || leaseSafetyMargin.isNegative()) throw new IllegalArgumentException("Lease safety margin cannot be negative");
        if (maxOutputBytes < 1024 || maxOutputBytes > 16 * 1024 * 1024) throw new IllegalArgumentException("Output limit must be between 1 KiB and 16 MiB");
    }

    public static PowerShellExecutionConfig fromEnvironment(Map<String, String> env) {
        boolean enabled = Boolean.parseBoolean(env.getOrDefault("EP_POWERSHELL_EXECUTION_ENABLED", "false"));
        String executable = env.getOrDefault("EP_POWERSHELL_EXE", "powershell.exe").trim();
        String scripts = env.getOrDefault("EP_SCRIPTS_DIRECTORY", "scripts").trim();
        long margin;
        int output;
        try {
            margin = Long.parseLong(env.getOrDefault("EP_JOB_LEASE_SAFETY_MARGIN_SECONDS", "15"));
            output = Integer.parseInt(env.getOrDefault("EP_POWERSHELL_MAX_OUTPUT_BYTES", "8388608"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PowerShell execution numeric settings must be whole numbers");
        }
        return new PowerShellExecutionConfig(enabled, executable, Path.of(scripts).toAbsolutePath().normalize(),
                Duration.ofSeconds(margin), output);
    }
}