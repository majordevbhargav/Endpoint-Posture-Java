package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.api.HeartbeatResponse;
import com.endpointposture.workerruntime.api.WorkerApiClient;
import com.endpointposture.workerruntime.api.WorkerApiException;
import com.endpointposture.workerruntime.api.WorkerTransportException;
import com.endpointposture.workerruntime.credentials.CredentialStore;
import com.endpointposture.workerruntime.credentials.DpapiSecretProtector;
import com.endpointposture.workerruntime.credentials.SecretProtector;
import com.endpointposture.workerruntime.credentials.StoredCredentials;

import java.io.PrintStream;
import java.util.Map;

/**
 * PR6.1 entry point: load config, enroll once (or reuse stored credentials), prove the key with
 * one heartbeat, exit. The heartbeat and claim loops arrive in PR6.2.
 *
 * <p>Exit codes: 0 ok, 2 configuration, 3 startup/enrollment, 4 key rejected, 5 backend or network error.</p>
 */
public final class WorkerMain {
    private WorkerMain() {}

    public static void main(String[] args) {
        if (!DpapiSecretProtector.isWindows()) {
            System.err.println("This worker runs on Windows only (DPAPI credential storage).");
            System.exit(2);
        }
        System.exit(run(System.getenv(), System.out, System.err, new DpapiSecretProtector()));
    }

    static int run(Map<String, String> env, PrintStream out, PrintStream err, SecretProtector protector) {
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

        try {
            HeartbeatResponse hb = WorkerApiClient.authenticated(config, c.apiKey()).heartbeat();
            out.println("Heartbeat accepted: status=" + hb.status()
                    + ", interval=" + hb.heartbeatIntervalSeconds() + "s.");
            return 0;
        } catch (WorkerApiException.Unauthorized e) {
            err.println("The backend rejected this worker's key. The worker may have been revoked.");
            return 4;
        } catch (WorkerApiException | WorkerTransportException e) {
            err.println("Heartbeat failed: " + e.getMessage());
            return 5;
        }
    }
}