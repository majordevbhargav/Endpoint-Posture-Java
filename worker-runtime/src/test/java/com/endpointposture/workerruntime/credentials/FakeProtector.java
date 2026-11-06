package com.endpointposture.workerruntime.credentials;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Reversible stand-in for DPAPI so tests run on any OS. */
public final class FakeProtector implements SecretProtector {
    @Override
    public String protect(String s) {
        return "enc:" + Base64.getEncoder().encodeToString(
                new StringBuilder(s).reverse().toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String unprotect(String v) {
        if (!v.startsWith("enc:")) throw new SecretProtectionException("bad blob");
        String reversed = new String(Base64.getDecoder().decode(v.substring(4)), StandardCharsets.UTF_8);
        return new StringBuilder(reversed).reverse().toString();
    }
}