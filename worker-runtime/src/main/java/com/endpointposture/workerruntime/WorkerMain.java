package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.api.WorkerApiClient;
import com.endpointposture.workerruntime.credentials.CredentialStore;
import com.endpointposture.workerruntime.credentials.DpapiSecretProtector;
import com.endpointposture.workerruntime.credentials.SecretProtector;
import com.endpointposture.workerruntime.credentials.StoredCredentials;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;

/** Windows worker entry point. Enrollment is a startup operation; lifecycle then runs until stopped. */
public final class WorkerMain {
    private WorkerMain() {}

    public static void main(String[] args) {
        if (!DpapiSecretProtector.isWindows()) {
            System.err.println("This worker runs on Windows only (DPAPI credential storage).");
            System.exit(2);
        }

        Thread workerThread = Thread.currentThread();
        Thread shutdownHook = new Thread(workerThread::interrupt, "worker-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        int exitCode;
        try {
            exitCode = run(System.getenv(), System.out, System.err, new DpapiSecretProtector(),
                    duration -> Thread.sleep(duration.toMillis()));
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already in progress.
            }
        }
        System.exit(exitCode);
    }

    static int run(Map<String, String> env, PrintStream out, PrintStream err, SecretProtector protector) {
        return run(env, out, err, protector, duration -> Thread.sleep(duration.toMillis()));
    }

    static int run(Map<String, String> env, PrintStream out, PrintStream err,
                   SecretProtector protector, WorkerLifecycle.Sleeper sleeper) {
        WorkerConfig config;
        try {
            config = WorkerConfig.fromEnvironment(env);
        } catch (IllegalArgumentException e) {
            err.println("Configuration error: " + e.getMessage());
            return 2;
        }

        CredentialStore store = new CredentialStore(config.stateFile(), protector);
        WorkerBootstrap.Result boot;
        try {
            boot = new WorkerBootstrap(config, store, WorkerApiClient.unauthenticated(config)).ensureEnrolled();
        } catch (WorkerBootstrap.BootstrapException e) {
            err.println("Startup error: " + e.getMessage());
            return 3;
        }

        StoredCredentials c = boot.credentials();
        out.println((boot.newlyEnrolled() ? "Enrolled as worker '" : "Using stored credentials for worker '")
                + c.name() + "' (shard " + c.shard() + ").");

        WorkerLifecycle lifecycle = new WorkerLifecycle(
                WorkerApiClient.authenticated(config, c.apiKey()),
                null, // PR6.3 supplies the executor; never lease a job before it can be executed.
                sleeper, err);
        return lifecycle.run();
    }
}
