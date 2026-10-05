package com.endpointposture.job;

/**
 * Raw outcome of an agent process.
 *
 * @param exitCode  process exit code, or -1 if it was killed on timeout
 * @param timedOut  true if the outer timeout fired
 * @param rawOutput stdout and stderr merged
 */
public record AgentRunResult(int exitCode, boolean timedOut, String rawOutput) {}