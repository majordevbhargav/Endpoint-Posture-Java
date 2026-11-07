package com.endpointposture.workerruntime.execution;

/** Boundary between local execution and future reporting (PR6.4). */
public interface JobResultHandoff {
    void accept(CompletedJobResult result) throws Exception;

    /** True while accepted results have not been acknowledged/drained by the reporting layer. */
    boolean hasPendingResults();
}