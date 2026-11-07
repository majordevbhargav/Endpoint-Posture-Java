package com.endpointposture.workerruntime.credentials;

public class CredentialStoreException extends RuntimeException {
    public CredentialStoreException(String message) { super(message); }

    public CredentialStoreException(String message, Throwable cause) { super(message, cause); }
}