package com.endpointposture.job;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Runs the agent as a child process of the backend (the only runner today).
 * Output is drained on its own thread so a chatty agent can never block on a
 * full pipe, and the process is killed if it exceeds the timeout.
 */
@Component
public class LocalProcessAgentRunner implements AgentRunner {

    @Override
    public AgentRunResult run(AgentCommand command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command.command()).redirectErrorStream(true);
        pb.environment().putAll(command.environment());
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
                // process killed or stream closed: whatever was read so far is kept
            }
        });
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = process.waitFor(command.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }

        if (!finished) {
            process.destroyForcibly();
        }

        drain.join(2000);

        return new AgentRunResult(finished ? process.exitValue() : -1, !finished, output.toString());
    }
}