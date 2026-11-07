package com.endpointposture.workerruntime;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestServer implements AutoCloseable {
    public record Recorded(String method, String path, String workerKey, String body) {}

    private final HttpServer server;
    public final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void on(String path, int status, String body) {
        on(path, status, body, Map.of());
    }

    public void on(String path, int status, String body, Map<String, String> headers) {
        server.createContext(path, ex -> {
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("X-Worker-Key"), reqBody));
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
            ex.close();
        });
    }


    public void onSequence(String path, int[] statuses, String body) {
        AtomicInteger index = new AtomicInteger();
        server.createContext(path, ex -> {
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("X-Worker-Key"), reqBody));
            int status = statuses[Math.min(index.getAndIncrement(), statuses.length - 1)];
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
            ex.close();
        });
    }

    @Override
    public void close() {
        server.stop(0);
    }
}