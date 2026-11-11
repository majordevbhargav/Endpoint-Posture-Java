package com.endpointposture.workerruntime.execution;

/** Boundary between local execution and future reporting (PR6.4). */
public interface JobResultHandoff {
    void accept(CompletedJobResult result) throws Exception;

    /** True while accepted results have not been acknowledged/drained by the reporting layer. */
    boolean hasPendingResults();

    /** Durable stores may retry transient write failures without losing their caller's snapshot. */
    default boolean durable() { return false; }

    default boolean retryPersistenceFailure(Exception failure) { return false; }
}
