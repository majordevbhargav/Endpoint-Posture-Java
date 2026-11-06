package com.endpointposture.workerruntime.credentials;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CredentialStoreTest {
    private static final String KEY = "epw_super-secret-key";

    @TempDir Path dir;

    private StoredCredentials creds() {
        return new StoredCredentials(UUID.randomUUID(), "w1", "default", URI.create("http://h:8090"), KEY);
    }

    private CredentialStore store() {
        return new CredentialStore(dir.resolve("state.json"), new FakeProtector());
    }

    @Test
    void emptyWhenNothingIsSaved() {
        assertTrue(store().load().isEmpty());
    }

    @Test
    void roundTripsAndNeverWritesThePlainKey() throws Exception {
        StoredCredentials c = creds();
        store().save(c);

        assertEquals(c, store().load().orElseThrow());
        assertFalse(Files.readString(dir.resolve("state.json")).contains(KEY));
    }

    @Test
    void overwritingLeavesOnlyTheStateFile() throws Exception {
        store().save(creds());
        store().save(creds());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void corruptOrIncompleteFilesAreRejected() throws Exception {
        Path f = dir.resolve("state.json");
        Files.writeString(f, "{not json");
        assertThrows(CredentialStoreException.class, () -> store().load());
        Files.writeString(f, "");
        assertThrows(CredentialStoreException.class, () -> store().load());
        Files.writeString(f, "{\"format\":1,\"name\":\"w\"}");
        assertThrows(CredentialStoreException.class, () -> store().load());
        Files.writeString(f, "{\"format\":99}");
        assertThrows(CredentialStoreException.class, () -> store().load());
    }

    @Test
    void aKeyThatCannotBeDecryptedGivesAHelpfulError() throws Exception {
        store().save(creds());
        Path f = dir.resolve("state.json");
        Files.writeString(f, Files.readString(f).replace("enc:", "xxx:"));
        var ex = assertThrows(CredentialStoreException.class, () -> store().load());
        assertTrue(ex.getMessage().contains("enroll again"));
    }

    @Test
    void aDirectoryInPlaceOfTheFileIsRejected() throws Exception {
        Files.createDirectory(dir.resolve("state.json"));
        assertThrows(CredentialStoreException.class, () -> store().load());
    }

    @Test
    void credentialsRejectMalformedKeysAndRedactToString() {
        assertThrows(IllegalArgumentException.class, () -> new StoredCredentials(
                UUID.randomUUID(), "w", "s", URI.create("http://h"), "not-a-key"));
        assertFalse(creds().toString().contains(KEY));
    }
}