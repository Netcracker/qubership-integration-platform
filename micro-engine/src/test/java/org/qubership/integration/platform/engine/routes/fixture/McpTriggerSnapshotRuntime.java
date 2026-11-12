package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultExchange;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class McpTriggerSnapshotRuntime {
    private static final Set<String> PARAMETERS = Set.of("nodeId", "operation", "toolName", "parallelArguments");
    private final CamelContext context;
    private final McpTriggerSnapshotServer server;
    private final String toolName;
    private final List<Exchange> received = new CopyOnWriteArrayList<>();
    private final List<Exchange> completed = new CopyOnWriteArrayList<>();
    private final Set<String> receivedExchangeIds = ConcurrentHashMap.newKeySet();
    private final Set<String> completedExchangeIds = ConcurrentHashMap.newKeySet();
    private volatile List<Object> lastResponses = List.of();
    private volatile boolean listed;
    private volatile CountDownLatch concurrentEntries;
    private volatile CountDownLatch concurrentRelease;

    McpTriggerSnapshotRuntime(CamelContext context, McpTriggerSnapshotServer server, String toolName) {
        this.context = context;
        this.server = server;
        this.toolName = toolName;
    }

    public static String registryName(String nodeId) {
        return "snapshot-mcp-trigger:" + nodeId;
    }

    public static void validateParameters(Map<String, Object> parameters) {
        for (String key : parameters.keySet()) {
            if (!PARAMETERS.contains(key)) {
                throw new IllegalArgumentException("Unsupported MCP trigger driver parameter: " + key);
            }
        }
        String operation = String.valueOf(parameters.getOrDefault("operation", "call"));
        if (!Set.of("call", "list", "concurrent").contains(operation)) {
            throw new IllegalArgumentException("Unsupported MCP trigger driver operation: " + operation);
        }
        if ("concurrent".equals(operation)
                && (!(parameters.get("parallelArguments") instanceof List<?> calls) || calls.isEmpty())) {
            throw new IllegalArgumentException("Concurrent MCP calls require parallelArguments.");
        }
    }

    public Exchange send(SnapshotScenarioInvocation invocation, Map<String, Object> parameters) throws Exception {
        validateParameters(parameters);
        String operation = String.valueOf(parameters.getOrDefault("operation", "call"));
        String name = String.valueOf(parameters.getOrDefault("toolName", toolName));
        listed = "list".equals(operation);
        Object response = switch (operation) {
            case "list" -> server.list();
            case "concurrent" -> concurrent(name, (List<?>) parameters.get("parallelArguments"));
            default -> server.call(name, invocation.getBody() == null ? Map.of() : invocation.getBody());
        };
        lastResponses = response instanceof List<?> responses ? new ArrayList<>(responses) : List.of(response);
        Exchange result = new DefaultExchange(context);
        result.getMessage().setBody(response);
        return result;
    }

    private List<Object> concurrent(String name, List<?> arguments) throws Exception {
        concurrentEntries = new CountDownLatch(arguments.size());
        concurrentRelease = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(arguments.size())) {
            List<Future<Map<String, Object>>> calls = new ArrayList<>();
            for (Object input : arguments) {
                calls.add(executor.submit(() -> server.call(name, input)));
            }
            try {
                assertTrue(concurrentEntries.await(10, TimeUnit.SECONDS), "Every concurrent MCP request must enter its generated trigger.");
            } finally {
                concurrentRelease.countDown();
            }
            List<Object> results = new ArrayList<>();
            for (Future<Map<String, Object>> call : calls) {
                results.add(call.get(25, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release();
            concurrentEntries = null;
            concurrentRelease = null;
        }
    }

    void received(Exchange exchange) throws Exception {
        assertEquals(server.registrationRoute(toolName), exchange.getFromRouteId(),
                "Native MCP handler must retain its registration event's route identity.");
        assertTrue(exchange.getMessage().getBody() instanceof Map, "Native MCP arguments must reach Camel as a map.");
        Exchange captured = exchange.copy();
        captured.getMessage().setBody(new LinkedHashMap<>((Map<?, ?>) exchange.getMessage().getBody()));
        received.add(captured);
        receivedExchangeIds.add(exchange.getExchangeId());
        CountDownLatch entries = concurrentEntries;
        CountDownLatch release = concurrentRelease;
        if (entries != null) {
            entries.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS), "Concurrent MCP requests must be released during cleanup.");
        }
    }

    void completed(Exchange exchange) {
        if (receivedExchangeIds.contains(exchange.getExchangeId()) && completedExchangeIds.add(exchange.getExchangeId())) {
            completed.add(exchange.copy());
        }
    }

    void reset() {
        received.clear();
        completed.clear();
        receivedExchangeIds.clear();
        completedExchangeIds.clear();
        lastResponses = List.of();
        listed = false;
    }

    List<Exchange> receivedExchanges() {
        return List.copyOf(received);
    }

    long businessCount() {
        return completed.stream().filter(exchange -> Boolean.TRUE.equals(exchange.getProperty("snapshotMcpScriptReached"))).count();
    }

    List<Object> lastResponses() {
        return lastResponses;
    }

    boolean listed() {
        return listed;
    }

    void release() {
        CountDownLatch gate = concurrentRelease;
        if (gate != null) {
            gate.countDown();
        }
    }

}
