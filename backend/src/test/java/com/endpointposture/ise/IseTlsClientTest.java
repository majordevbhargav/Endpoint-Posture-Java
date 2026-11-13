package com.endpointposture.ise;

import com.endpointposture.ise.config.IseProperties;
import com.endpointposture.session.IseSessionClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.*;

class IseTlsClientTest {

    private static final char[] STORE_PASSWORD = "test-password".toCharArray();
    private static final String SESSION_XML = "<activeList><activeSession>"
            + "<calling_station_id>AA:BB:CC:DD:EE:01</calling_station_id>"
            + "</activeSession></activeList>";

    @TempDir Path tempDir;
    private HttpsServer server;
    private Path serverKeyStore;
    private Path trustStore;

    @BeforeEach
    void startHttpsServer() throws Exception {
        serverKeyStore = tempDir.resolve("ise-test-server.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-noprompt",
                "-alias", "ise-test", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                "-keystore", serverKeyStore.toString(), "-storepass", new String(STORE_PASSWORD),
                "-keypass", new String(STORE_PASSWORD), "-dname", "CN=localhost", "-validity", "3650",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true).start();
        String keytoolOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), keytoolOutput);

        Path certificate = tempDir.resolve("ise-test-server.crt");
        runKeytool(keytool, "-exportcert", "-rfc", "-alias", "ise-test", "-keystore",
                serverKeyStore.toString(), "-storepass", new String(STORE_PASSWORD),
                "-file", certificate.toString());
        trustStore = tempDir.resolve("ise-private-ca.p12");
        runKeytool(keytool, "-importcert", "-noprompt", "-alias", "ise-test-ca", "-file",
                certificate.toString(), "-storetype", "PKCS12", "-keystore", trustStore.toString(),
                "-storepass", new String(STORE_PASSWORD));

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(serverKeyStore)) {
            keys.load(input, STORE_PASSWORD);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, STORE_PASSWORD);
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManagers.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        server.createContext("/admin/API/mnt/Session/ActiveList", exchange -> respond(exchange,
                200, "application/xml", SESSION_XML));
        server.createContext("/ers/config/endpoint", exchange -> respond(exchange,
                200, "application/json", "{\"SearchResult\":{\"resources\":[]}}"));
        server.start();
    }

    @AfterEach
    void stopHttpsServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void bothIseClientsAcceptCertificateFromConfiguredPrivateTruststore() {
        IseProperties properties = properties();
        properties.setTrustStore(trustStore.toString());
        properties.setTrustStorePassword(new String(STORE_PASSWORD));

        IseSessionClient.SessionPoll poll = new IseSessionClient(properties).fetchActiveSessions();
        assertTrue(poll.ok(), poll.error());
        assertEquals(1, poll.sessions().size());
        assertTrue(new ErsIseTransport(properties).reachable());
    }

    @Test
    void bothIseClientsRejectCertificateAbsentFromConfiguredTrust() {
        IseProperties properties = properties();

        IseSessionClient.SessionPoll poll = new IseSessionClient(properties).fetchActiveSessions();
        assertFalse(poll.ok());
        assertFalse(new ErsIseTransport(properties).reachable());
    }

    private IseProperties properties() {
        IseProperties properties = new IseProperties();
        properties.setBaseUrl("https://localhost:" + server.getAddress().getPort());
        properties.setUsername("test-user");
        properties.setPassword("test-password");
        properties.setVerifyTls(true);
        return properties;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void runKeytool(Path keytool, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(keytool.toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
