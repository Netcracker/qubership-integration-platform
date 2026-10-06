package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.OVERRIDE_CONTEXT_PARAMS;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SnapshotContextRuntimeTest {
    @Mock
    private CamelExchangeContextPropagation contextPropagation;

    @Test
    void shouldInitializeEachInvocationAtTheEntryRouteWhenSendersUseNestedRoutes() throws Exception {
        List<Map<String, Object>> initializations = stubContextPropagation();
        List<Map<String, Object>> entrySnapshots = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        SnapshotFixtureBinding first = binding("first", Map.of("one", "request-one", "two", "request-two"));
        SnapshotFixtureBinding second = binding("second", Map.of("one", "request-one", "two", "request-two"));

        try (SnapshotContextRuntime runtime = runtime(first, second);
                DefaultCamelContext context = new DefaultCamelContext()) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(noErrorHandler());
                    from("direct:entry").routeId("entry")
                            .process(exchange -> entrySnapshots.add(snapshot(exchange)))
                            .to("direct:first")
                            .to("direct:second");
                    from("direct:first").process(exchange -> runtime.senderEntered(first, exchange))
                            .setProperty(OVERRIDE_CONTEXT_PARAMS, constant(Map.of("X-Request-Id", "first-hop")))
                            .process("contextPropagationProcessor")
                            .process(exchange -> requests.add(exchange.getMessage().getHeader("X-Request-Id", String.class)))
                            .setHeader("X-Request-Id", constant("response-request-id"))
                            .removeProperty(OVERRIDE_CONTEXT_PARAMS)
                            .process("contextRestoreProcessor");
                    from("direct:second").process(exchange -> runtime.senderEntered(second, exchange))
                            .process("contextPropagationProcessor")
                            .process(exchange -> requests.add(exchange.getMessage().getHeader("X-Request-Id", String.class)))
                            .process("contextRestoreProcessor");
                }
            });
            runtime.configure(context, context.getRouteDefinitions(), List.of(context.getRouteDefinition("entry")));
            context.start();

            try (ProducerTemplate producer = context.createProducerTemplate()) {
                for (String id : List.of("one", "two")) {
                    SnapshotScenarioInvocation invocation = invocation(id);
                    runtime.beforeInvocation();
                    Exchange result = producer.request("direct:entry", exchange -> {
                        exchange.getMessage().setHeader("X-Request-Id", "request-" + id);
                        exchange.getMessage().setHeader("Authorization", "Bearer token");
                    });

                    assertNull(result.getException());
                    assertEquals("Bearer token", result.getMessage().getHeader("Authorization"));
                    assertEquals(Map.of("X-Request-Id", "request-" + id), snapshot(result));
                    runtime.verifyInvocation(invocation);
                }
            }
        }

        assertEquals(List.of("first-hop", "request-one", "first-hop", "request-two"), requests);
        assertEquals(List.of(Map.of("X-Request-Id", "request-one"), Map.of("X-Request-Id", "request-two")), entrySnapshots);
        assertEquals(List.of(
                Map.of("X-Request-Id", "request-one", "Authorization", "Bearer token"),
                Map.of("X-Request-Id", "request-two", "Authorization", "Bearer token")), initializations);
    }

    @Test
    void shouldKeepTheEntrySnapshotWhenCatchInvokesAnotherSender() throws Exception {
        List<Map<String, Object>> initializations = stubContextPropagation();
        List<String> requests = new ArrayList<>();
        SnapshotFixtureBinding primary = binding("primary", Map.of("first", "request-first"));
        SnapshotFixtureBinding fallback = binding("fallback", Map.of("first", "request-first"));

        try (SnapshotContextRuntime runtime = runtime(primary, fallback);
                DefaultCamelContext context = new DefaultCamelContext()) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(noErrorHandler());
                    from("direct:entry").routeId("entry")
                            .doTry().to("direct:primary")
                            .doCatch(IllegalStateException.class).to("direct:fallback").end();
                    from("direct:primary").process(exchange -> runtime.senderEntered(primary, exchange))
                            .setProperty(OVERRIDE_CONTEXT_PARAMS, constant(Map.of("X-Request-Id", "primary-hop")))
                            .doTry()
                                .process("contextPropagationProcessor")
                                .setHeader("X-Request-Id", constant("failed-response-request-id"))
                                .process(exchange -> {
                                    throw new IllegalStateException("Primary request failed");
                                })
                            .doFinally()
                                .removeProperty(OVERRIDE_CONTEXT_PARAMS)
                                .process("contextRestoreProcessor")
                            .end();
                    from("direct:fallback").process(exchange -> runtime.senderEntered(fallback, exchange))
                            .process("contextPropagationProcessor")
                            .process(exchange -> requests.add(exchange.getMessage().getHeader("X-Request-Id", String.class)))
                            .process("contextRestoreProcessor");
                }
            });
            runtime.configure(context, context.getRouteDefinitions(), List.of(context.getRouteDefinition("entry")));
            context.start();
            runtime.beforeInvocation();

            try (ProducerTemplate producer = context.createProducerTemplate()) {
                Exchange result = producer.request("direct:entry", exchange -> exchange.getMessage()
                        .setHeader("X-Request-Id", "request-first"));

                assertNull(result.getException());
                assertEquals(Map.of("X-Request-Id", "request-first"), snapshot(result));
                runtime.verifyInvocation(invocation("first"));
            }
        }

        assertEquals(List.of("request-first"), requests);
        assertEquals(List.of(Map.of("X-Request-Id", "request-first")), initializations);
    }

    private SnapshotContextRuntime runtime(SnapshotFixtureBinding... bindings) {
        return new SnapshotContextRuntime(List.of(bindings), List.of(), () -> contextPropagation);
    }

    private List<Map<String, Object>> stubContextPropagation() {
        List<Map<String, Object>> initializations = new ArrayList<>();
        AtomicReference<Map<String, Object>> current = new AtomicReference<>(Map.of());
        doAnswer(call -> {
            Map<String, Object> headers = call.getArgument(0);
            initializations.add(Map.copyOf(headers));
            current.set(Map.of("X-Request-Id", headers.get("X-Request-Id")));
            return null;
        }).when(contextPropagation).initRequestContext(anyMap());
        when(contextPropagation.createContextSnapshot()).thenAnswer(call -> Map.copyOf(current.get()));
        when(contextPropagation.getHeadersForCurrentContext()).thenAnswer(call -> Map.copyOf(current.get()));
        doAnswer(call -> {
            current.set(call.getArgument(0));
            return null;
        }).when(contextPropagation).activateContextSnapshot(anyMap());
        doAnswer(call -> {
            Map<String, Object> overridden = new LinkedHashMap<>(current.get());
            overridden.putAll(call.getArgument(0));
            current.set(overridden);
            return null;
        }).when(contextPropagation).overrideHeadersForCurrentContext(anyMap());
        doAnswer(call -> {
            Map<String, Object> headers = call.getArgument(0);
            headers.remove("X-Request-Id");
            return null;
        }).when(contextPropagation).removeContextHeaders(anyMap());
        doAnswer(call -> {
            Map<String, Object> headers = call.getArgument(0);
            headers.putAll(current.get());
            return null;
        }).when(contextPropagation).propagateExchangeHeaders(anyMap());
        return initializations;
    }

    private static Map<String, Object> snapshot(Exchange exchange) {
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = exchange.getProperty(REQUEST_CONTEXT_PROPAGATION_SNAPSHOT, Map.class);
        return snapshot;
    }

    private static SnapshotFixtureBinding binding(String id, Map<String, String> requestIds) {
        SnapshotFixtureDefinition definition = new SnapshotFixtureDefinition(id, "http-sender-http", "test", id, null);
        Map<String, SnapshotFixtureInteraction> interactions = new LinkedHashMap<>();
        requestIds.forEach((invocationId, requestId) -> {
            SnapshotFixtureResponse response = new SnapshotFixtureResponse(200, null, "response", Map.of(), Map.of(
                    "expectedSendCount", 1, "expectedRestoredContext", Map.of("X-Request-Id", requestId)));
            SnapshotFixtureRequestExpectation expected = new SnapshotFixtureRequestExpectation(1, null, null,
                    null, null, null, null, Map.of(), Map.of());
            interactions.put(invocationId, new SnapshotFixtureInteraction(id, response, expected, null, null, null, null, null));
        });
        return new SnapshotFixtureBinding(definition, interactions);
    }

    private static SnapshotScenarioInvocation invocation(String id) {
        return ObjectMappers.getObjectMapper().convertValue(Map.of("id", id), SnapshotScenarioInvocation.class);
    }
}
