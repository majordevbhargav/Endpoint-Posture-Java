package com.endpointposture.workerruntime.credentials;

/** Encrypts a secret so it can be stored on disk. The Windows implementation uses DPAPI. */
public interface SecretProtector {
    String protect(String plaintext);

    String unprotect(String protectedValue);
}