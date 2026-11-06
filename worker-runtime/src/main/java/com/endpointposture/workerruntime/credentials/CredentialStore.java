package com.endpointposture.workerruntime.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * One JSON state file. The API key is stored only in protected form. Writes go to a temp file
 * in the same directory and are moved into place, so a crash cannot leave a half-written file.
 */
public final class CredentialStore {
    private static final int FORMAT = 1;

    private final Path file;
    private final SecretProtector protector;
    private final ObjectMapper mapper = new ObjectMapper();

    public CredentialStore(Path file, SecretProtector protector) {
        this.file = file.toAbsolutePath();
        this.protector = protector;
    }

    public Optional<StoredCredentials> load() {
        if (!Files.isRegularFile(file)) {
            if (Files.exists(file)) throw new CredentialStoreException("State path is not a file: " + file);
            return Optional.empty();
        }
        JsonNode n;
        try {
            n = mapper.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw corrupt(e);
        }
        if (n == null || !n.isObject()) throw corrupt(null);
        if (n.path("format").asInt(-1) != FORMAT)
            throw new CredentialStoreException("Unsupported credential file format in " + file);
        String key;
        try {
            key = protector.unprotect(text(n, "protectedApiKey"));
        } catch (SecretProtectionException e) {
            throw new CredentialStoreException("Cannot decrypt the stored worker key. It can only be decrypted by the "
                    + "Windows account on the machine that enrolled. If the service account changed, delete " + file
                    + " and enroll again with a new token.", e);
        }
        try {
            return Optional.of(new StoredCredentials(UUID.fromString(text(n, "workerId")), text(n, "name"),
                    text(n, "shard"), URI.create(text(n, "backendUrl")), key));
        } catch (IllegalArgumentException e) {
            throw corrupt(e);
        }
    }

    public void save(StoredCredentials c) {
        String protectedKey;
        try {
            protectedKey = protector.protect(c.apiKey());
        } catch (SecretProtectionException e) {
            throw new CredentialStoreException("Cannot encrypt the worker key: " + e.getMessage(), e);
        }
        Path tmp = null;
        try {
            ObjectNode n = mapper.createObjectNode();
            n.put("format", FORMAT);
            n.put("workerId", c.workerId().toString());
            n.put("name", c.name());
            n.put("shard", c.shard());
            n.put("backendUrl", c.backendUrl().toString());
            n.put("protectedApiKey", protectedKey);
            byte[] data = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(n);

            Path dir = file.getParent();
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, "state-", ".tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            throw new CredentialStoreException("Cannot write credential file " + file + ": " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank())
            throw new CredentialStoreException("Credential file is missing '" + field + "'");
        return v.asText();
    }

    private CredentialStoreException corrupt(Throwable cause) {
        return new CredentialStoreException("Credential file " + file + " is unreadable or corrupt", cause);
    }
}