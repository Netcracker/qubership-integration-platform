package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.ToolManager;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.qubership.integration.platform.engine.camel.listeners.RouteEventListener;
import org.qubership.integration.platform.engine.camel.processors.EmptyProcessor;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.McpTriggerInfo;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTriggerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    @Override
    public String getId() {
        return "mcp-trigger";
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        return new Fixture(deploymentId, bindings);
    }

    private static final class Fixture implements SnapshotFixture {
        private static final String EVENTS = "snapshot-mcp-events";
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final List<ObservedTool> tools = new ArrayList<>();
        private CamelContext context;
        private McpTriggerSnapshotServer server;
        private NativeEvents events;
        private List<RouteDefinition> deploymentRoutes;
        private String nativeDeploymentId;
        private boolean verifyContextClose;

        private Fixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = bindings;
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void beforeRouteLoad(CamelContext camelContext) throws Exception {
            context = camelContext;
            server = McpTriggerSnapshotServer.acquire(context);
            events = context.getRegistry().lookupByNameAndType(EVENTS, NativeEvents.class);
            if (events == null) {
                events = new NativeEvents(server);
                context.getRegistry().bind(EVENTS, events);
                context.getManagementStrategy().addEventNotifier(events);
            }
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            deploymentRoutes = List.copyOf(routes);
            SnapshotFixtureRouteScope.bind(context, routes, deploymentId + ":mcp-trigger",
                    Map.of("chainFinishProcessor", new EmptyProcessor()));
            for (SnapshotFixtureBinding binding : bindings) {
                String node = binding.definition().getNodeId();
                RouteDefinition route = SnapshotRouteNodes.requireSingle(SnapshotRouteNodes.findById(routes, node),
                        "MCP trigger requires one generated trigger step").route();
                assertEquals("direct:" + node, route.getInput().getUri(), "Generated native MCP route entrypoint");
                McpTriggerInfo metadata = context.getRegistry().lookupByNameAndType("McpTriggerInfo-" + node, McpTriggerInfo.class);
                assertNotNull(metadata, "MCP fixture must use generated tool metadata.");
                McpTriggerSnapshotRuntime runtime = new McpTriggerSnapshotRuntime(context, server, metadata.getName());
                context.getRegistry().bind(McpTriggerSnapshotRuntime.registryName(node), runtime);
                AdviceWith.adviceWith(context, route, false, advice -> advice.weaveById(node).before().process(runtime::received));
                context.getManagementStrategy().addEventNotifier(new EventNotifierSupport() {
                    @Override
                    public boolean isEnabled(CamelEvent event) {
                        return event instanceof CamelEvent.ExchangeCompletedEvent || event instanceof CamelEvent.ExchangeFailedEvent;
                    }

                    @Override
                    public void notify(CamelEvent event) {
                        if (event instanceof CamelEvent.ExchangeEvent exchangeEvent) {
                            runtime.completed(exchangeEvent.getExchange());
                        }
                    }
                });
                tools.add(new ObservedTool(binding, route, metadata, runtime));
            }
            nativeDeploymentId = context.getRegistry().lookupByNameAndType(
                    "DeploymentInfo-" + tools.getFirst().route().getGroup(), DeploymentInfo.class).getId();
            boolean defer = bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .filter(interaction -> interaction.getResponse() != null)
                    .anyMatch(interaction -> Boolean.TRUE.equals(interaction.getResponse().getProperties().get("deferRoutesUntilInvocation")));
            if (defer) {
                ModelCamelContext model = (ModelCamelContext) context;
                for (RouteDefinition route : deploymentRoutes) {
                    model.removeRouteDefinition(route);
                }
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            tools.forEach(tool -> tool.runtime().reset());
            Set<String> actions = tools.stream().map(tool -> controls(tool, invocation).get("consumerAction"))
                    .filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toSet());
            if (actions.contains("remove") || actions.contains("reload")) {
                ModelCamelContext model = (ModelCamelContext) context;
                for (RouteDefinition route : deploymentRoutes) {
                    if (context.getRoute(route.getId()) != null) {
                        context.getRouteController().stopRoute(route.getId());
                    }
                    if (model.getRouteDefinition(route.getId()) != null) {
                        model.removeRouteDefinition(route);
                    }
                }
                if (actions.contains("reload")) {
                    model.addRouteDefinitions(deploymentRoutes);
                }
            }
            for (ObservedTool tool : tools) {
                Map<String, Object> properties = controls(tool, invocation);
                verifyContextClose |= Boolean.TRUE.equals(properties.get("verifyContextClose"));
                Object action = properties.get("consumerAction");
                if ("stop-start".equals(action)) {
                    context.getRouteController().stopRoute(tool.route().getId());
                    context.getRouteController().startRoute(tool.route().getId());
                }
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (ObservedTool tool : tools) {
                SnapshotFixtureInteraction interaction = tool.binding().interaction(invocation.getId());
                Map<String, Object> properties = controls(tool, invocation);
                if (tool.runtime().listed()) {
                    JsonNode listed = listedResponse(tool);
                    assertTrue(listed.isArray(), "The actual driver tools/list response must contain a tools array.");
                    Map<String, JsonNode> actual = new TreeMap<>();
                    for (JsonNode entry : listed) {
                        assertTrue(entry.isObject() && entry.path("name").isTextual(), "Every listed MCP tool must have a name.");
                        assertEquals(null, actual.put(entry.path("name").asText(), entry), "tools/list must not duplicate a tool name.");
                    }
                    Map<String, JsonNode> registered = new TreeMap<>();
                    ObjectMapper mapper = new ObjectMapper();
                    for (ToolManager.ToolInfo info : server.manager()) {
                        if (McpServer.DEFAULT.equals(info.serverName())) {
                            registered.put(info.name(), mapper.readTree(info.asJson().encode()));
                        }
                    }
                    assertEquals(registered, actual, "Actual tools/list must match all currently registered tool definitions.");
                    if (properties.get("expectedToolPresent") instanceof Boolean expected) {
                        boolean ownToolListed = false;
                        for (JsonNode entry : listed) {
                            ownToolListed |= tool.metadata().getName().equals(entry.path("name").asText())
                                    && nativeDeploymentId.equals(entry.path("_meta").path("deploymentId").asText());
                        }
                        assertEquals(expected, ownToolListed, "Actual tools/list must reflect the selected deployment's tool presence.");
                    }
                }
                if (interaction.getExpectedRequest() != null) {
                    assertEquals(interaction.getExpectedRequest().getCount(), tool.runtime().receivedExchanges().size(),
                            "Native MCP trigger entry count for " + tool.metadata().getName());
                }
                if (properties.get("expectedBusinessCount") instanceof Number expected) {
                    assertEquals(expected.longValue(), tool.runtime().businessCount(), "MCP business execution count");
                }
                if (properties.get("expectedCapturedArguments") instanceof List<?> expected) {
                    List<Object> actual = tool.runtime().receivedExchanges().stream().map(exchange -> exchange.getMessage().getBody()).toList();
                    if (Boolean.TRUE.equals(properties.get("unorderedCapturedArguments"))) {
                        assertEquals(multiset(expected), multiset(actual), "Native MCP argument map multiset");
                    } else {
                        assertEquals(expected, actual, "Native MCP argument maps");
                    }
                }
                if (properties.get("expectedResponseMultiset") instanceof List<?> expected) {
                    assertEquals(multiset(expected), multiset(tool.runtime().lastResponses()), "Native MCP response multiset");
                }
                if (properties.get("expectedRegistrationFailures") instanceof Number expected) {
                    List<RegistrationFailure> failures = events.failures.stream()
                            .filter(failure -> tool.route().getGroup().equals(failure.group())).toList();
                    long actual = failures.size();
                    assertTrue(actual >= expected.longValue(), "Native MCP registration failure count: " + events.failures);
                    String kind = (String) properties.get("registrationFailureKind");
                    if ("schema".equals(kind)) {
                        assertTrue(failures.stream().anyMatch(failure -> hasCause(failure.exception(), JsonProcessingException.class)),
                                "Malformed schema must fail through native Jackson parsing.");
                    } else if ("duplicate-name".equals(kind)) {
                        String message = "A tool with name [" + tool.metadata().getName() + "] already exits";
                        assertTrue(failures.stream().anyMatch(failure -> hasCause(failure.exception(), IllegalArgumentException.class)
                                        && message.equals(failure.exception().getMessage())),
                                "Duplicate registration must retain the native name-collision error.");
                    }
                }
                if (properties.get("expectedToolPresent") instanceof Boolean expected) {
                    ToolManager.ToolInfo registered = server.manager().getTool(tool.metadata().getName());
                    DeploymentInfo deployment = context.getRegistry().lookupByNameAndType(
                            "DeploymentInfo-" + tool.route().getGroup(), DeploymentInfo.class);
                    boolean owned = registered != null && deployment.getId().equals(registered.metadata().get(MetaKey.of("deploymentId")));
                    assertEquals(expected, owned, "Native MCP registration for the selected deployment");
                }
                if (Boolean.TRUE.equals(properties.get("verifyMetadata"))) {
                    verifyMetadata(tool);
                }
            }
        }

        private void verifyMetadata(ObservedTool tool) throws Exception {
            ToolManager.ToolInfo info = server.manager().getTool(tool.metadata().getName());
            assertNotNull(info, "Generated MCP tool must register with the native manager.");
            assertEquals(tool.metadata().getTitle(), info.title(), "MCP tool title");
            assertEquals(tool.metadata().getDescription(), info.description(), "MCP tool description");
            ToolManager.ToolAnnotations hints = info.annotations().orElseThrow();
            assertEquals(tool.metadata().getReadOnly(), hints.readOnlyHint(), "MCP read-only hint");
            assertEquals(tool.metadata().getDestructive(), hints.destructiveHint(), "MCP destructive hint");
            assertEquals(tool.metadata().getIdempotent(), hints.idempotentHint(), "MCP idempotent hint");
            assertEquals(tool.metadata().getOpenWorld(), hints.openWorldHint(), "MCP open-world hint");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode listed = tool.runtime().listed() ? listedResponse(tool) : mapper.valueToTree(server.list()).path("result").path("tools");
            assertTrue(listed.isArray(), "Native tools/list must contain a tools array.");
            JsonNode advertised = null;
            for (JsonNode entry : listed) {
                if (tool.metadata().getName().equals(entry.path("name").asText())) {
                    advertised = entry;
                    break;
                }
            }
            assertNotNull(advertised, "Native tools/list must advertise the generated tool.");
            assertEquals(tool.metadata().getName(), advertised.path("name").asText(), "MCP wire tool name");
            assertEquals(tool.metadata().getTitle(), advertised.path("title").asText(), "MCP wire tool title");
            assertEquals(tool.metadata().getDescription(), advertised.path("description").asText(), "MCP wire tool description");
            assertEquals(mapper.readTree(tool.metadata().getInputSchema()), advertised.path("inputSchema"),
                    "MCP advertised input schema");
            if (tool.metadata().getOutputSchema() == null || tool.metadata().getOutputSchema().isBlank()) {
                assertTrue(!advertised.has("outputSchema"), "A text-result tool must omit outputSchema.");
            } else {
                assertEquals(mapper.readTree(tool.metadata().getOutputSchema()), advertised.path("outputSchema"), "MCP wire output schema");
            }
            assertHint(advertised, "readOnlyHint", hints.readOnlyHint());
            assertHint(advertised, "destructiveHint", hints.destructiveHint());
            assertHint(advertised, "idempotentHint", hints.idempotentHint());
            assertHint(advertised, "openWorldHint", hints.openWorldHint());
            assertEquals(tool.metadata().getTitle(), advertised.path("annotations").path("title").asText(), "MCP wire annotation title");
            assertTrue(info.metadata().containsKey(MetaKey.of("deploymentId")), "Native registration must retain deployment metadata.");
            assertEquals(nativeDeploymentId, advertised.path("_meta").path("deploymentId").asText(), "MCP wire deployment metadata");
        }

        private static JsonNode listedResponse(ObservedTool tool) {
            assertEquals(1, tool.runtime().lastResponses().size(), "tools/list must have one native protocol response.");
            return new ObjectMapper().valueToTree(tool.runtime().lastResponses().getFirst()).path("result").path("tools");
        }

        private static void assertHint(JsonNode tool, String field, boolean expected) {
            JsonNode hint = tool.path("annotations").path(field);
            assertTrue(hint.isBoolean(), "Native MCP annotation must contain Boolean " + field);
            assertEquals(expected, hint.booleanValue(), "MCP wire annotation " + field);
        }

        private static Map<String, Object> controls(ObservedTool tool, SnapshotScenarioInvocation invocation) {
            var response = tool.binding().interaction(invocation.getId()).getResponse();
            return response == null ? Map.of() : response.getProperties();
        }

        private static Map<Object, Long> multiset(List<?> values) {
            return values.stream().collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        }

        private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (type.isInstance(current)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void releasePendingWork() {
            tools.forEach(tool -> tool.runtime().release());
        }

        @Override
        public void verify() {
        }

        @Override
        public void close() throws Exception {
            releasePendingWork();
            if (server != null) {
                try {
                    if (verifyContextClose) {
                        assertTrue(StreamSupport.stream(server.manager().spliterator(), false)
                                        .noneMatch(tool -> nativeDeploymentId.equals(tool.metadata().get(MetaKey.of("deploymentId")))),
                                "Closing the native Camel context must unregister its MCP tools.");
                    }
                } finally {
                    server.close();
                }
            }
        }
    }

    private record ObservedTool(SnapshotFixtureBinding binding, RouteDefinition route,
                                McpTriggerInfo metadata, McpTriggerSnapshotRuntime runtime) {
    }

    private static final class NativeEvents extends EventNotifierSupport {
        private final McpTriggerSnapshotServer server;
        private final RouteEventListener delegate = CDI.current().select(RouteEventListener.class).get();
        private final List<RegistrationFailure> failures = new CopyOnWriteArrayList<>();

        private NativeEvents(McpTriggerSnapshotServer server) {
            this.server = server;
        }

        @Override
        public boolean isEnabled(CamelEvent event) {
            return event instanceof CamelEvent.RouteAddedEvent || event instanceof CamelEvent.RouteRemovedEvent;
        }

        @Override
        public void notify(CamelEvent event) throws Exception {
            Set<String> previous = StreamSupport.stream(server.manager().spliterator(), false)
                    .map(ToolManager.ToolInfo::name).collect(Collectors.toSet());
            try {
                delegate.notify(event);
                if (event instanceof CamelEvent.RouteAddedEvent added) {
                    StreamSupport.stream(server.manager().spliterator(), false)
                            .filter(tool -> !previous.contains(tool.name()))
                            .forEach(tool -> server.registered(tool.name(), added.getRoute().getId()));
                }
            } catch (Exception failure) {
                String group = event instanceof CamelEvent.RouteEvent route ? route.getRoute().getGroup() : "";
                failures.add(new RegistrationFailure(group, failure));
                throw failure;
            }
        }
    }

    private record RegistrationFailure(String group, Exception exception) {
    }
}
