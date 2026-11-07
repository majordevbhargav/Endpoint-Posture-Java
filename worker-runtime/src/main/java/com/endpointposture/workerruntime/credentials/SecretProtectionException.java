package com.endpointposture.workerruntime.credentials;

public class SecretProtectionException extends RuntimeException {
    public SecretProtectionException(String message) { super(message); }

    public SecretProtectionException(String message, Throwable cause) { super(message, cause); }
}