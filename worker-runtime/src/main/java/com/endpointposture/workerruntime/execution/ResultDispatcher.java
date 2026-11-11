package com.endpointposture.workerruntime.execution;

import com.endpointposture.workerruntime.api.WorkerApiClient;
import com.endpointposture.workerruntime.api.WorkerApiException;
import com.endpointposture.workerruntime.api.WorkerResultAcknowledgement;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Single background dispatcher for durable results. Every retry sends stored bytes unchanged. */
public final class ResultDispatcher implements AutoCloseable {
    private static final int MAX_ATTEMPTS_DELAY_SECONDS = 300;
    private final int maxCumulativeExtensionSeconds;
    private final SqliteJobResultHandoff outbox;
    private final WorkerApiClient api;
    private final PrintStream err;
    private final Thread thread;
    private volatile boolean closing;

    public ResultDispatcher(SqliteJobResultHandoff outbox, WorkerApiClient api, PrintStream err) {
        this(outbox,api,err,900);
    }
    public ResultDispatcher(SqliteJobResultHandoff outbox, WorkerApiClient api, PrintStream err,
                            int maxCumulativeExtensionSeconds) {
        if(maxCumulativeExtensionSeconds<0) throw new IllegalArgumentException("maximum lease extension cannot be negative");
        this.outbox=outbox; this.api=api; this.err=err;
        this.maxCumulativeExtensionSeconds=maxCumulativeExtensionSeconds;
        thread=new Thread(this::run,"worker-result-dispatcher"); thread.setDaemon(false); thread.start();
    }

    private void run() {
        long lastStatusLog=0;
        while(!closing && !Thread.currentThread().isInterrupted()) {
            try {
                if(System.nanoTime()-lastStatusLog>TimeUnit.MINUTES.toNanos(5)) {
                    OutboxStatistics stats=outbox.statistics();
                    err.println("Worker outbox: pending="+stats.pending()+", permanentFailures="+stats.permanentFailures()
                            +", delivered="+stats.delivered()+", retries="+stats.totalRetries()+", oldestUnresolved="
                            +(stats.oldestUnresolvedAt()==null?"none":stats.oldestUnresolvedAt())+", databaseBytes="+stats.databaseBytes()
                            +", lastBackendSuccess="+outbox.lastBackendSuccess());
                    lastStatusLog=System.nanoTime();
                }
                var results=outbox.pending(1);
                if(results.isEmpty()) { Thread.sleep(500); continue; }
                var item=results.get(0); Instant now=Instant.now();
                if(item.nextAttemptAt().isAfter(now)) {
                    Thread.sleep(Math.min(1000,Math.max(1,Duration.between(now,item.nextAttemptAt()).toMillis()))); continue;
                }
                dispatch(item,now);
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            catch(RuntimeException e) {
                err.println("CRITICAL: worker result dispatcher/store failure; pending result retained: "
                        + safeDiagnostic(e));
                try { Thread.sleep(5000); } catch(InterruptedException x) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static String safeDiagnostic(RuntimeException e) {
        String message=e.getMessage();
        if(message==null||message.isBlank()) return e.getClass().getSimpleName();
        message=message.replaceAll("[\\r\\n\\t]"," ");
        return message.length()>300?message.substring(0,300):message;
    }

    private void dispatch(PersistedJobResult item, Instant now) {
        int extended=item.cumulativeLeaseExtensionSeconds();
        if(now.isBefore(item.deliveryDeadline()) && extended<maxCumulativeExtensionSeconds) {
            long graceRemaining=Math.max(0,Duration.between(now,item.deliveryDeadline()).toSeconds());
            int seconds=(int)Math.min(300,Math.min(maxCumulativeExtensionSeconds-extended,graceRemaining));
            if(seconds>0) {
                try {
                    if(outbox.reserveLeaseExtension(item.result().claimedJob().jobId(),item.result().claimedJob().attempt(),extended,extended+seconds)) {
                        extended+=seconds;
                        try { api.renewLease(item.result().claimedJob().jobId(),item.result().claimedJob().attempt(),seconds); }
                        catch(WorkerApiException.StaleAttempt ignored) { /* result submission still gets its fenced decision */ }
                        catch(RuntimeException e) { err.println("Lease renewal failed for pending worker result; delivery will continue: "+e.getClass().getSimpleName()); }
                    }
                } catch(RuntimeException e) { err.println("Could not reserve result lease-extension budget; delivery continues without extending it."); }
            }
        }
        try {
            WorkerResultAcknowledgement ack=api.submitResult(item.result().claimedJob().jobId(),item.requestPayload());
            String outcome=item.result().envelope().path("collectionOutcome").asText();
            if(ack.attempt()!=item.result().claimedJob().attempt() || !outcome.equals(ack.collectionOutcome()))
                throw new WorkerApiException(200,"Worker result acknowledgement identity did not match submitted result");
            byte[] ackBytes=com.endpointposture.workerruntime.api.Json.mapper().writeValueAsBytes(ack);
            outbox.markDelivered(item.result().claimedJob().jobId(),ack.attempt(),ackBytes);
            outbox.recordBackendSuccess(Instant.now());
            err.println("Worker result acknowledged for job "+ack.jobId()+" attempt "+ack.attempt()+".");
        } catch(WorkerApiException.RateLimited e) {
            retry(item,now,extended,e.retryAfter().orElse(null),"HTTP 429");
        } catch(WorkerApiException.StaleAttempt e) {
            outbox.markPermanentFailure(item.result().claimedJob().jobId(),item.result().claimedJob().attempt(),"HTTP 409 stale or conflicting result; retained for operator review");
            err.println("Worker result rejected with HTTP 409; retained as permanent failure for operator review.");
        } catch(WorkerApiException e) {
            if(e.status()==408 || e.status()==425 || e.status()>=500) retry(item,now,extended,null,"HTTP "+e.status());
            else {
                outbox.markPermanentFailure(item.result().claimedJob().jobId(),item.result().claimedJob().attempt(),"HTTP "+e.status()+" permanent response");
                err.println("Worker result permanently rejected with HTTP "+e.status()+"; retained in outbox.");
            }
        } catch(Exception e) {
            retry(item,now,extended,null,e.getClass().getSimpleName());
        }
    }

    private void retry(PersistedJobResult item,Instant now,int extended,Duration retryAfter,String reason) {
        int count=item.retryCount()+1;
        long base=Math.min(MAX_ATTEMPTS_DELAY_SECONDS,1L<<Math.min(9,Math.max(0,count-1)));
        long delay=retryAfter==null?Math.max(1,(long)(base*(0.5+ThreadLocalRandom.current().nextDouble()))):
                Math.min(MAX_ATTEMPTS_DELAY_SECONDS,Math.max(1,retryAfter.toSeconds()));
        outbox.updateDelivery(item.result().claimedJob().jobId(),item.result().claimedJob().attempt(),count,
                now.plusSeconds(delay),extended,reason);
    }

    @Override public void close() {
        closing=true; thread.interrupt();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        boolean interrupted=false;
        while(thread.isAlive()) {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0) break;
            try { thread.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining))); }
            catch(InterruptedException e) { interrupted=true; thread.interrupt(); }
        }
        if(interrupted) Thread.currentThread().interrupt();
    }
}
