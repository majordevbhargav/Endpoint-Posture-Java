package com.endpointposture.job;

import java.io.IOException;

/**
 * Starts one PowerShell agent and returns what it printed. The only place that
 * knows <em>how</em> an agent is launched. {@link JobWorker} decides <em>what</em>
 * to launch and how to read the result.
 *
 * <p>{@link LocalProcessAgentRunner} runs it on this machine. A future remote
 * runner would send the same {@link AgentCommand} to a Windows runner over HTTPS
 * (needs TLS and a runner credential) without any change to {@link JobWorker}.</p>
 */
public interface AgentRunner {

    /**
     * Runs the command and waits for it, killing it once the timeout passes.
     *
     * @return exit code, whether it timed out, and everything it printed
     * @throws IOException          if the process could not be started
     * @throws InterruptedException if the calling thread was interrupted (the process is killed)
     */
    AgentRunResult run(AgentCommand command) throws IOException, InterruptedException;
}