package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.WorkerLifecycle;
import com.endpointposture.workerruntime.api.ClaimedJob;

import java.io.PrintStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous single-job adapter. The lifecycle thread remains free to heartbeat while this
 * handler executes. Pending results keep the handler busy until the reporting layer drains them.
 */
public final class PowerShellJobHandler implements WorkerLifecycle.JobHandler, AutoCloseable {
    @FunctionalInterface interface JobParser { ValidatedJob parse(ClaimedJob claimed) throws Exception; }
    @FunctionalInterface interface JobExecutor { PowerShellExecutor.ExecutionResult execute(ClaimedJob claimed, ValidatedJob job) throws Exception; }

    private final JobParser parser;
    private final JobExecutor executor;
    private final PrintStream err;
    private final JobResultHandoff resultHandoff;
    private final InMemoryJobResultHandoff localHandoff;
    private final AtomicBoolean active = new AtomicBoolean();
    /** Sticky fail-closed state: a claimed job that was not handed off must stop further claims. */
    private final AtomicBoolean blocked = new AtomicBoolean();
    /** Temporary retention while a durable store is unavailable; never clears the sticky block on shutdown. */
    private volatile CompletedJobResult pendingPersistence;
    private volatile Thread executionThread;

    public PowerShellJobHandler(URI backend, PowerShellExecutionConfig config, PrintStream err) {
        this(backend, config, err, new InMemoryJobResultHandoff());
    }

    public PowerShellJobHandler(URI backend, PowerShellExecutionConfig config, PrintStream err,
                                JobResultHandoff resultHandoff) {
        this(claimed -> new JobSpecParser(backend).parse(claimed),
                (claimed, job) -> new PowerShellExecutor(config).execute(claimed, job), err, resultHandoff);
    }

    PowerShellJobHandler(JobParser parser, JobExecutor executor, PrintStream err,
                         JobResultHandoff resultHandoff) {
        this.parser = parser;
        this.executor = executor;
        this.err = err;
        if (parser == null || executor == null) throw new IllegalArgumentException("Parser and executor are required");
        if (resultHandoff == null) throw new IllegalArgumentException("A result handoff is required");
        this.resultHandoff = resultHandoff;
        this.localHandoff = resultHandoff instanceof InMemoryJobResultHandoff memory ? memory : null;
    }

    /** Available to PR6.4 and diagnostics when using the default bounded in-memory handoff. */
    public InMemoryJobResultHandoff localResultHandoff() {
        if (localHandoff == null) throw new IllegalStateException("A custom result handoff was configured");
        return localHandoff;
    }

    @Override public boolean isBusy() {
        return blocked.get() || active.get() || pendingPersistence != null || resultHandoff.hasPendingResults();
    }

    @Override public void handle(ClaimedJob claimed) {
        if (blocked.get()) throw new IllegalStateException("Worker is blocked after an unhanded claimed job");
        if (!active.compareAndSet(false, true)) throw new IllegalStateException("A worker job is already active");
        Thread thread = new Thread(() -> {
            AtomicBoolean handedOff = new AtomicBoolean();
            try {
                ValidatedJob job = parser.parse(claimed);
                PowerShellExecutor.ExecutionResult result = executor.execute(claimed, job);
                CompletedJobResult completed = new CompletedJobResult(claimed, job, result.exitCode(),
                        result.result(), result.stdoutTruncated(), result.stderrTruncated());
                persistUntilAccepted(completed);
                handedOff.set(true);
                err.println("PowerShell result handed off for job " + claimed.jobId()
                        + " (exit=" + result.exitCode() + ", collectionOutcome="
                        + result.result().path("collectionOutcome").asText("UNKNOWN") + ").");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err.println("PowerShell execution cancelled for job " + claimed.jobId());
            } catch (Exception e) {
                err.println("PowerShell execution/handoff failed for job " + claimed.jobId() + ": "
                        + e.getClass().getSimpleName() + " (details withheld)");
            } finally {
                if (!handedOff.get()) {
                    blocked.set(true);
                    err.println("CRITICAL: worker blocked after job " + claimed.jobId()
                            + " failed before completed-result handoff; restart/recovery is required.");
                }
                active.set(false);
                executionThread = null;
            }
        }, "powershell-job-" + claimed.jobId());
        thread.setDaemon(false);
        executionThread = thread;
        try {
            thread.start();
        } catch (RuntimeException startFailure) {
            blocked.set(true);
            active.set(false);
            executionThread = null;
            throw startFailure;
        }
    }

    private void persistUntilAccepted(CompletedJobResult completed) throws InterruptedException {
        pendingPersistence=completed;
        long delaySeconds=1;
        while(true) {
            try {
                resultHandoff.accept(completed);
                pendingPersistence=null;
                return;
            } catch(Exception e) {
                if(!resultHandoff.durable() || !resultHandoff.retryPersistenceFailure(e)) {
                    if(!resultHandoff.durable()) pendingPersistence=null;
                    throw new IllegalStateException("Result handoff failed",e);
                }
                err.println("CRITICAL: durable result persistence failed for job "
                        + completed.claimedJob().jobId() + " (" + e.getClass().getSimpleName()
                        + ": " + safeDiagnostic(e) + "); retaining the completed result in memory and retrying while claims remain blocked.");
                Thread.sleep(delaySeconds*1000);
                delaySeconds=Math.min(60,delaySeconds*2);
            }
        }
    }

    private static String safeDiagnostic(Exception e) {
        String message=e.getMessage();
        if(message==null||message.isBlank()) return e.getClass().getSimpleName();
        message=message.replaceAll("[\\r\\n\\t]"," ");
        return message.length()>240?message.substring(0,240):message;
    }

    @Override public void close() {
        Thread thread = executionThread;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(3000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (blocked.get()) {
            err.println("CRITICAL: worker is blocked after a claimed job failed before result handoff.");
        }
        if (pendingPersistence != null) {
            err.println("CRITICAL: shutdown before completed result reached durable storage for job "
                    + pendingPersistence.claimedJob().jobId() + "; result remains only in process memory.");
        }
        try {
            if (resultHandoff.hasPendingResults()) {
                err.println("CRITICAL: worker shutdown with completed results still pending; "
                        + "they were not acknowledged or removed from the handoff. Drain/export them before restart.");
            }
        } catch(RuntimeException e) {
            err.println("CRITICAL: could not inspect durable result storage during shutdown: "
                    + safeDiagnostic(e));
        }
    }
}
