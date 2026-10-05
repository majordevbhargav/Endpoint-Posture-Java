package com.endpointposture.job;

import java.util.List;
import java.util.Map;

/**
 * One agent invocation.
 *
 * @param command        full argument list, starting with the executable
 * @param timeoutSeconds outer limit; the process is killed if it runs longer
 * @param environment    extra environment variables (for example the agent API key,
 *                       never put on the command line)
 */
public record AgentCommand(List<String> command, int timeoutSeconds, Map<String, String> environment) {}