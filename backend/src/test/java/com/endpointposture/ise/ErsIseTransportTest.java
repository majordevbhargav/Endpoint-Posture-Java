package com.endpointposture.ise;

import com.endpointposture.ise.config.IseProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErsIseTransportTest {

    private HttpServer server;
    private int port;
    private List<RecordedRequest> recordedRequests;

    record RecordedRequest(String method, String path, String accept, String contentType, String auth, String body) {}

    @BeforeEach
    void setUp() throws IOException {
        recordedRequests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(String contextPath, HttpHandler handler) {
        server.createContext(contextPath, exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().toString();
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            recordedRequests.add(new RecordedRequest(method, path, accept, contentType, auth, body));
            handler.handle(exchange);
        });
    }

    private IseProperties buildProps(IseProperties.Mode mode) {
        IseProperties p = new IseProperties();
        p.setBaseUrl("http://localhost:" + port);
        p.setUsername("admin");
        p.setPassword("secret");
        p.setVerifyTls(true);
        p.setEnforcementMode(mode);
        p.setAncPolicyName("Quarantine");
        return p;
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendXml(HttpExchange exchange, int status, String xml) throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void publishPosture_createPath_whenEndpointNotFound() {
        // GET /ers/config/endpoint?filter=mac.EQ... returns empty list
        handle("/ers/config/endpoint", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, "{\"SearchResult\":{\"resources\":[]}}");
            } else if ("POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 201, "{\"ERSEndPoint\":{\"id\":\"ep-123\"}}");
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        });

        ErsIseTransport transport = new ErsIseTransport(buildProps(IseProperties.Mode.ANC));
        IseResult result = transport.publishPosture("AA:BB:CC:DD:EE:01", "COMPLIANT", "none");

        assertTrue(result.success());
        assertEquals("Posture shared with ISE as COMPLIANT", result.detail());

        // Verify request headers
        assertEquals(2, recordedRequests.size());
        RecordedRequest postReq = recordedRequests.get(1);
        assertEquals("POST", postReq.method());
        assertEquals("application/json", postReq.accept());
        assertEquals("application/json", postReq.contentType());
        assertTrue(postReq.auth().startsWith("Basic "));
        assertTrue(postReq.body().contains("AA:BB:CC:DD:EE:01"));
        assertTrue(postReq.body().contains("COMPLIANT"));
    }

    @Test
    void publishPosture_updatePath_whenEndpointAlreadyExists() {
        handle("/ers/config/endpoint", exchange -> {
            String path = exchange.getRequestURI().toString();
            if ("GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, "{\"SearchResult\":{\"resources\":[{\"id\":\"existing-ep-999\"}]}}");
            } else if ("PUT".equals(exchange.getRequestMethod()) && path.contains("/existing-ep-999")) {
                sendJson(exchange, 200, "{\"ERSEndPoint\":{\"id\":\"existing-ep-999\"}}");
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        });

        ErsIseTransport transport = new ErsIseTransport(buildProps(IseProperties.Mode.ANC));
        IseResult result = transport.publishPosture("AA:BB:CC:DD:EE:02", "NON_COMPLIANT", "Firewall off");

        assertTrue(result.success());
        assertEquals(2, recordedRequests.size());
        RecordedRequest putReq = recordedRequests.get(1);
        assertEquals("PUT", putReq.method());
        assertTrue(putReq.path().contains("/existing-ep-999"));
        assertTrue(putReq.body().contains("NON_COMPLIANT"));
        assertTrue(putReq.body().contains("Firewall off"));
    }

    @Test
    void applyAnc_applyAndClear() {
        handle("/ers/config/ancendpoint/apply", exchange -> {
            sendJson(exchange, 204, "");
        });
        handle("/ers/config/ancendpoint/clear", exchange -> {
            sendJson(exchange, 204, "");
        });

        ErsIseTransport transport = new ErsIseTransport(buildProps(IseProperties.Mode.ANC));

        // Apply restrict
        IseResult restrictRes = transport.publishEnforcement("AA:BB:CC:DD:EE:03", EnforcementAction.RESTRICT, "CustomQuarantine");
        assertTrue(restrictRes.success());
        assertEquals("ANC_APPLIED", restrictRes.detail());

        RecordedRequest applyReq = recordedRequests.get(0);
        assertEquals("POST", applyReq.method());
        assertEquals("/ers/config/ancendpoint/apply", applyReq.path());
        assertEquals("application/json", applyReq.accept());
        assertEquals("application/json", applyReq.contentType());
        assertTrue(applyReq.body().contains("CustomQuarantine"));
        assertTrue(applyReq.body().contains("AA:BB:CC:DD:EE:03"));

        // Clear restrict
        IseResult clearRes = transport.publishEnforcement("AA:BB:CC:DD:EE:03", EnforcementAction.CLEAR, null);
        assertTrue(clearRes.success());
        assertEquals("ANC_CLEARED", clearRes.detail());

        RecordedRequest clearReq = recordedRequests.get(1);
        assertEquals("POST", clearReq.method());
        assertEquals("/ers/config/ancendpoint/clear", clearReq.path());
        assertTrue(clearReq.body().contains("AA:BB:CC:DD:EE:03"));
    }

    @Test
    void attributeMode_noSession_returnsFailure() {
        handle("/admin/API/mnt/Session/MACAddress/AA:BB:CC:DD:EE:04", exchange -> {
            sendXml(exchange, 200, "<session></session>");
        });

        ErsIseTransport transport = new ErsIseTransport(buildProps(IseProperties.Mode.ATTRIBUTE));
        IseResult result = transport.publishEnforcement("AA:BB:CC:DD:EE:04", EnforcementAction.RESTRICT, null);

        assertFalse(result.success());
        assertTrue(result.detail().contains("NO_ACTIVE_SESSION"));
    }

    @Test
    void attributeMode_sessionWithNoPsn_returnsFailure() {
        handle("/admin/API/mnt/Session/MACAddress/AA:BB:CC:DD:EE:05", exchange -> {
            String xml = "<session><user_name>alice</user_name><nas_ip_address>10.0.0.1</nas_ip_address></session>";
            sendXml(exchange, 200, xml);
        });

        ErsIseTransport transport = new ErsIseTransport(buildProps(IseProperties.Mode.ATTRIBUTE));
        IseResult result = transport.publishEnforcement("AA:BB:CC:DD:EE:05", EnforcementAction.RESTRICT, null);

        assertFalse(result.success());
        assertTrue(result.detail().contains("SESSION_FOUND_BUT_NO_PSN"));
    }

    @Test
    void attributeMode_successReauth() {
        handle("/admin/API/mnt/Session/MACAddress/AA:BB:CC:DD:EE:06", exchange -> {
            String xml = "<session><user_name>bob</user_name><acs_server>psn01.corp.internal</acs_server></session>";
            sendXml(exchange, 200, xml);
        });
        handle("/admin/API/mnt/CoA/Reauth/psn01.corp.internal/AA:BB:CC:DD:EE:06/1", exchange -> {
            sendXml(exchange, 200, "<remoteCoA><results>true</results></remoteCoA>");
        });

        ErsIseTransport transport = new ErsIseTransport(buildProps(IseProperties.Mode.ATTRIBUTE));
        IseResult result = transport.publishEnforcement("AA:BB:CC:DD:EE:06", EnforcementAction.RESTRICT, null);

        assertTrue(result.success());
        assertEquals("REAUTH_APPLIED", result.detail());
    }

    @Test
    void transportHandlesHttpFailureGracefully() {
        handle("/ers/config/endpoint", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });

        ErsIseTransport transport = new ErsIseTransport(buildProps(IseProperties.Mode.ANC));
        IseResult result = transport.publishPosture("AA:BB:CC:DD:EE:07", "COMPLIANT", null);

        assertFalse(result.success());
        assertTrue(result.detail() != null && !result.detail().isBlank());
    }
}
