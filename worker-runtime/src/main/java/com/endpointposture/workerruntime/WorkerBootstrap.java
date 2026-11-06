package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.api.Enrolled;
import com.endpointposture.workerruntime.api.WorkerApiClient;
import com.endpointposture.workerruntime.api.WorkerApiException;
import com.endpointposture.workerruntime.api.WorkerTransportException;
import com.endpointposture.workerruntime.credentials.CredentialStore;
import com.endpointposture.workerruntime.credentials.CredentialStoreException;
import com.endpointposture.workerruntime.credentials.StoredCredentials;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/** Loads stored credentials, or enrolls once with the single-use token and saves the key. */
public final class WorkerBootstrap {

    public record Result(StoredCredentials credentials, boolean newlyEnrolled) {}

    public static final class BootstrapException extends RuntimeException {
        public BootstrapException(String message) { super(message); }

        public BootstrapException(String message, Throwable cause) { super(message, cause); }
    }

    private final WorkerConfig config;
    private final CredentialStore store;
    private final WorkerApiClient enrollClient;

    public WorkerBootstrap(WorkerConfig config, CredentialStore store, WorkerApiClient enrollClient) {
        this.config = config;
        this.store = store;
        this.enrollClient = enrollClient;
    }

    public Result ensureEnrolled() {
        Optional<StoredCredentials> stored;
        try {
            stored = store.load();
        } catch (CredentialStoreException e) {
            throw new BootstrapException(e.getMessage(), e);
        }
        if (stored.isPresent()) {
            StoredCredentials c = stored.get();
            if (!sameOrigin(c.backendUrl(), config.backendUrl())) {
                throw new BootstrapException("Stored credentials belong to " + c.backendUrl()
                        + " but EP_BACKEND_URL is " + config.backendUrl() + ". Refusing to send the key to a "
                        + "different server. Delete the state file to enroll again.");
            }
            return new Result(c, false);
        }
        if (config.enrollmentToken() == null) {
            throw new BootstrapException("This worker is not enrolled and EP_ENROLLMENT_TOKEN is not set. "
                    + "Ask an admin for a single-use enrollment token.");
        }

        Enrolled enrolled;
        try {
            enrolled = enrollClient.enroll(config.enrollmentToken());
        } catch (WorkerApiException.EnrollmentRejected e) {
            throw new BootstrapException("The enrollment token was rejected: " + e.getMessage()
                    + ". Ask an admin for a new single-use token.", e);
        } catch (WorkerApiException.RateLimited e) {
            throw new BootstrapException("Enrollment is rate limited"
                    + e.retryAfter().map(d -> "; try again in " + d.toSeconds() + " s").orElse("") + ".", e);
        } catch (WorkerApiException e) {
            throw new BootstrapException("Enrollment failed (HTTP " + e.status() + "): " + e.getMessage(), e);
        } catch (WorkerTransportException e) {
            throw new BootstrapException("Could not reach the backend to enroll: " + e.getMessage()
                    + ". If this was a timeout the token may already be used; ask an admin to check the worker list.", e);
        }

        StoredCredentials creds;
        try {
            creds = new StoredCredentials(enrolled.workerId(), enrolled.name(), enrolled.shard(),
                    config.backendUrl(), enrolled.apiKey());
        } catch (RuntimeException e) {
            throw new BootstrapException("The backend returned an invalid enrollment response.", e);
        }
        try {
            store.save(creds);
        } catch (CredentialStoreException e) {
            throw new BootstrapException("Enrolled as worker '" + enrolled.name() + "' (id " + enrolled.workerId()
                    + ") but could not save the key: " + e.getMessage() + " The token is now used and the key cannot "
                    + "be shown again. An admin must revoke this worker and issue a new token.", e);
        }
        return new Result(creds, true);
    }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URI u) {
        if (u.getPort() != -1) return u.getPort();
        return u.getScheme().toLowerCase(Locale.ROOT).equals("https") ? 443 : 80;
    }
}