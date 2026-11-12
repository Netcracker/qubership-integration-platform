package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.http.runtime.HttpMcpServerRecorder;
import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
import io.quarkiverse.mcp.server.runtime.config.McpServersRuntimeConfig;
import io.quarkus.runtime.RuntimeValue;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpTriggerSnapshotServer implements AutoCloseable {
    private static final String REGISTRY_NAME = "snapshot-mcp-server";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> registrationRoutes = new ConcurrentHashMap<>();
    private final List<Throwable> routerFailures = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicLong nextRequest = new AtomicLong();
    private final HttpServer server;
    private final URI address;
    private String sessionId;
    private int owners;

    static McpTriggerSnapshotServer acquire(CamelContext context) throws Exception {
        synchronized (context) {
            McpTriggerSnapshotServer server = context.getRegistry().lookupByNameAndType(REGISTRY_NAME, McpTriggerSnapshotServer.class);
            if (server == null) {
                server = new McpTriggerSnapshotServer();
                context.getRegistry().bind(REGISTRY_NAME, server);
            }
            server.owners++;
            return server;
        }
    }

    private McpTriggerSnapshotServer() throws Exception {
        Vertx vertx = CDI.current().select(Vertx.class).get();
        HttpMcpServerRecorder recorder = new HttpMcpServerRecorder(
                new RuntimeValue<>(CDI.current().select(McpServersRuntimeConfig.class).get()),
                CDI.current().select(McpTriggerSnapshotDependencies.class).get().httpBuildTimeConfig());
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route("/mcp").handler(recorder.createMcpEndpointHandler(McpServer.DEFAULT));
        router.route().failureHandler(context -> {
            routerFailures.add(context.failure());
            context.next();
        });
        server = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        address = URI.create("http://127.0.0.1:" + server.actualPort() + "/mcp");
    }

    ToolManager manager() {
        return CDI.current().select(ToolManager.class).get();
    }

    void registered(String tool, String route) {
        registrationRoutes.put(tool, route);
    }

    String registrationRoute(String tool) {
        return registrationRoutes.get(tool);
    }

    synchronized void initialize() throws Exception {
        if (sessionId != null) {
            return;
        }
        Map<String, Object> initialize = Map.of(
                "protocolVersion", "2025-03-26",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "snapshot-mcp-client", "version", "1"));
        long id = nextRequest.incrementAndGet();
        HttpResponse<String> response = client.send(request(Map.of("jsonrpc", "2.0", "id", id,
                "method", "initialize", "params", initialize)), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Native MCP initialization status; body=" + response.body()
                + ", headers=" + response.headers().map() + ", routerFailures=" + routerFailures);
        sessionId = response.headers().firstValue(StreamableHttpMcpMessageHandler.MCP_SESSION_ID_HEADER).orElseThrow();
        JsonNode message = response(response, id);
        assertFalse(message.has("error"), "Native MCP initialization result: " + message);
        HttpResponse<String> initialized = client.send(request(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, initialized.statusCode(), "Native MCP initialized notification status");
    }

    Map<String, Object> call(String toolName, Object arguments) throws Exception {
        initialize();
        return rpc("tools/call", Map.of("name", toolName, "arguments", arguments));
    }

    Map<String, Object> list() throws Exception {
        initialize();
        return rpc("tools/list", Map.of());
    }

    private Map<String, Object> rpc(String method, Map<String, Object> parameters) throws Exception {
        long id = nextRequest.incrementAndGet();
        HttpResponse<String> result = client.send(request(Map.of("jsonrpc", "2.0", "id", id,
                "method", method, "params", parameters)), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode(), "Native MCP HTTP status: " + result.body());
        JsonNode message = response(result, id);
        Map<String, Object> projected = mapper.convertValue(message, LinkedHashMap.class);
        projected.remove("id");
        projected.remove("jsonrpc");
        return projected;
    }

    private HttpRequest request(Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(address)
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (sessionId != null) {
            builder.header(StreamableHttpMcpMessageHandler.MCP_SESSION_ID_HEADER, sessionId);
            builder.header("MCP-Protocol-Version", "2025-03-26");
        }
        return builder.build();
    }

    private JsonNode response(HttpResponse<String> response, long id) throws Exception {
        JsonNode result = null;
        if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            for (String line : response.body().lines().toList()) {
                if (line.startsWith("data:")) {
                    JsonNode message = mapper.readTree(line.substring(5).trim());
                    if (message.has("id") && message.path("id").asLong() == id) {
                        result = message;
                    }
                }
            }
        } else {
            result = mapper.readTree(response.body());
        }
        assertTrue(result != null && result.has("id"), "Native MCP response must carry its request ID: " + response.body());
        assertEquals("2.0", result.path("jsonrpc").asText(), "Native MCP JSON-RPC version");
        assertEquals(id, result.path("id").asLong(), "Native MCP response correlation");
        return result;
    }

    @Override
    public synchronized void close() throws Exception {
        if (--owners != 0) {
            return;
        }
        try {
            if (sessionId != null) {
                HttpRequest terminate = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(10))
                        .header(StreamableHttpMcpMessageHandler.MCP_SESSION_ID_HEADER, sessionId).DELETE().build();
                HttpResponse<String> response = client.send(terminate, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), "Native MCP session termination status");
            }
        } finally {
            try {
                server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                client.close();
            }
        }
    }
}
