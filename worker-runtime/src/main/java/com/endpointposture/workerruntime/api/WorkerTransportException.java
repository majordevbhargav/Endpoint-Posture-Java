package com.endpointposture.workerruntime.api;

/** The backend could not be reached (connection refused, timeout, interrupted). */
public class WorkerTransportException extends RuntimeException {
    public WorkerTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}