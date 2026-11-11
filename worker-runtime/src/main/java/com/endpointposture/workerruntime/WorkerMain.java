package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.api.WorkerApiClient;
import com.endpointposture.workerruntime.credentials.CredentialStore;
import com.endpointposture.workerruntime.credentials.DpapiSecretProtector;
import com.endpointposture.workerruntime.credentials.SecretProtector;
import com.endpointposture.workerruntime.credentials.StoredCredentials;
import com.endpointposture.workerruntime.execution.PowerShellExecutionConfig;
import com.endpointposture.workerruntime.execution.PowerShellJobHandler;
import com.endpointposture.workerruntime.execution.SqliteJobResultHandoff;
import com.endpointposture.workerruntime.execution.ResultDispatcher;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;

/** Windows worker entry point. Enrollment is a startup operation; lifecycle then runs until stopped. */
public final class WorkerMain {
    private WorkerMain() {
    }

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
            exitCode = run(
                    System.getenv(),
                    System.out,
                    System.err,
                    new DpapiSecretProtector(),
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

    static int run(
            Map<String, String> env,
            PrintStream out,
            PrintStream err,
            SecretProtector protector) {
        return run(env, out, err, protector, duration -> Thread.sleep(duration.toMillis()));
    }

    static int run(
            Map<String, String> env,
            PrintStream out,
            PrintStream err,
            SecretProtector protector,
            WorkerLifecycle.Sleeper sleeper) {
        WorkerConfig config;
        PowerShellExecutionConfig execution;
        Duration deliveryGrace;
        int maxLeaseExtension;

        try {
            config = WorkerConfig.fromEnvironment(env);
            execution = PowerShellExecutionConfig.fromEnvironment(env);
            deliveryGrace=Duration.ofMinutes(Long.parseLong(env.getOrDefault("EP_RESULT_DELIVERY_GRACE_MINUTES", "15")));
            maxLeaseExtension=Integer.parseInt(env.getOrDefault("EP_RESULT_MAX_CUMULATIVE_LEASE_EXTENSION_SECONDS", "900"));
            if(deliveryGrace.isZero()||deliveryGrace.isNegative()||maxLeaseExtension<0)
                throw new IllegalArgumentException("Result delivery grace must be positive and maximum lease extension cannot be negative");
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

        StoredCredentials credentials = boot.credentials();
        out.println((boot.newlyEnrolled() ? "Enrolled as worker '" : "Using stored credentials for worker '")
                + credentials.name() + "' (shard " + credentials.shard() + ").");

        WorkerApiClient api = WorkerApiClient.authenticated(config, credentials.apiKey());
        if (!execution.enabled()) {
            out.println(
                    "PowerShell execution is disabled. Set EP_POWERSHELL_EXECUTION_ENABLED=true "
                            + "after script contract review. Pending results will still be delivered.");
        }

        SqliteJobResultHandoff handoff;
        try {
            handoff = new SqliteJobResultHandoff(config.outboxFile(), credentials.workerId(), credentials.shard(),
                    deliveryGrace);
        } catch (RuntimeException e) {
            err.println("Startup error initializing durable result storage: " + e.getMessage());
            return 6;
        }
        PowerShellJobHandler handler = execution.enabled()
                ? new PowerShellJobHandler(config.backendUrl(), execution, err, handoff) : null;
        ResultDispatcher dispatcher = new ResultDispatcher(handoff, api, err,maxLeaseExtension);
        try {
            return new WorkerLifecycle(api, handler, sleeper, err).run();
        } finally {
            dispatcher.close();
            if (handler != null) handler.close();
            handoff.close();
        }
    }
}
