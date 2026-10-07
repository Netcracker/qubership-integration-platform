package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.context.propagation.core.ContextProvider;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextProvider;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ProcessDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.hc.core5.http.HttpHeaders;
import org.qubership.integration.platform.engine.camel.CorrelationIdSetter;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.camel.processors.CorrelationIdPropagationProcessor;
import org.qubership.integration.platform.engine.camel.processors.CorrelationIdReceiverProcessor;
import org.qubership.integration.platform.engine.camel.processors.context.propagation.ContextPropagationProcessor;
import org.qubership.integration.platform.engine.camel.processors.context.propagation.ContextRestoreProcessor;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

final class SnapshotContextRuntime implements AutoCloseable {
    private final List<SnapshotFixtureBinding> bindings;
    private final List<ContextProvider<?>> additionalContextProviders;
    private final Map<String, String> fixturesByExchangeId = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> restoredContexts = new ConcurrentHashMap<>();
    private List<ContextProvider<?>> originalContextProviders;
    private Map<String, Object> originalContext;
    private CamelExchangeContextPropagation contextPropagation;

    SnapshotContextRuntime(List<SnapshotFixtureBinding> bindings) {
        this(bindings, List.of());
    }

    SnapshotContextRuntime(List<SnapshotFixtureBinding> bindings, List<ContextProvider<?>> additionalContextProviders) {
        this.bindings = List.copyOf(bindings);
        this.additionalContextProviders = List.copyOf(additionalContextProviders);
    }

    void configure(CamelContext context, List<RouteDefinition> routes) throws Exception {
        List<RouteDefinition> contextRoutes = routes.stream().filter(SnapshotContextRuntime::usesContext).toList();
        if (contextRoutes.isEmpty()) {
            return;
        }
        originalContext = ContextManager.createContextSnapshot();
        originalContextProviders = List.copyOf(ContextManager.getContextProviders());
        ContextManager.register(additionalContextProviders);
        if (originalContextProviders.stream()
                .noneMatch(provider -> XRequestIdContextProvider.X_REQUEST_ID_CONTEXT_NAME.equals(provider.contextName()))) {
            ContextManager.register(List.of(new XRequestIdContextProvider()));
        }
        contextPropagation = CDI.current().select(CamelExchangeContextPropagation.class).get();
        ContextRestoreProcessor restore = new ContextRestoreProcessor(contextPropagation);
        Processor observedRestore = exchange -> {
            restore.process(exchange);
            String fixtureId = fixturesByExchangeId.get(exchange.getExchangeId());
            restoredContexts.computeIfAbsent(fixtureId, ignored -> new CopyOnWriteArrayList<>())
                    .add(immutableMap(contextPropagation.getHeadersForCurrentContext()));
        };
        SnapshotFixtureRouteScope.bind(context, contextRoutes, bindings.getFirst().definition().getProvider() + ":" + bindings.getFirst().definition().getDeploymentId(),
                Map.of(
                        "contextPropagationProcessor", new ContextPropagationProcessor(contextPropagation),
                        "contextRestoreProcessor", observedRestore,
                        "correlationIdPropagationProcessor", new CorrelationIdPropagationProcessor(ObjectMappers.getObjectMapper()),
                        "correlationIdReceiverProcessor", new CorrelationIdReceiverProcessor(
                                new CorrelationIdSetter(ObjectMappers.getObjectMapper()))
                ));
        for (RouteDefinition route : contextRoutes) {
            AdviceWith.adviceWith(context, route, false, advice -> advice.weaveAddFirst().process(this::initializeContext));
        }
    }

    void senderEntered(SnapshotFixtureBinding binding, Exchange exchange) {
        fixturesByExchangeId.put(exchange.getExchangeId(), binding.definition().getId());
    }

    void beforeInvocation() {
        fixturesByExchangeId.clear();
        restoredContexts.clear();
    }

    void verifyInvocation(SnapshotScenarioInvocation invocation) {
        for (SnapshotFixtureBinding binding : bindings) {
            var interaction = binding.interaction(invocation.getId());
            if (interaction.getResponse() == null) {
                continue;
            }
            Object expected = interaction.getResponse().getProperties().get("expectedRestoredContext");
            if (expected instanceof Map<?, ?> expectedContext) {
                String fixtureId = binding.definition().getId();
                List<Map<String, Object>> observed = restoredContexts.getOrDefault(fixtureId, List.of());
                Object expectedCount = interaction.getResponse().getProperties()
                        .getOrDefault("expectedSendCount", interaction.getExpectedRequest().getCount());
                assertEquals(expectedCount, observed.size(), "Snapshot fixture '" + fixtureId
                        + "' did not restore context after each send in invocation '" + invocation.getId() + "'.");
                for (Map<String, Object> actual : observed) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> expectedHeaders = (Map<String, Object>) expectedContext;
                    SnapshotValueAssertions.assertMapValues(expectedHeaders, new CaseInsensitiveMap<>(actual),
                            "Snapshot fixture '" + fixtureId + "' restored an unexpected context");
                }
            }
        }
    }

    private void initializeContext(Exchange exchange) {
        Map<String, Object> headers = exchange.getMessage().getHeaders();
        contextPropagation.initRequestContext(headers);
        Object authorization = headers.get(HttpHeaders.AUTHORIZATION);
        contextPropagation.removeContextHeaders(headers);
        if (authorization != null) {
            headers.put(HttpHeaders.AUTHORIZATION, authorization);
        }
        exchange.setProperty(REQUEST_CONTEXT_PROPAGATION_SNAPSHOT, contextPropagation.createContextSnapshot());
    }

    private static boolean usesContext(ProcessorDefinition<?> node) {
        return node instanceof ProcessDefinition process
                && ("contextPropagationProcessor".equals(process.getRef()) || "contextRestoreProcessor".equals(process.getRef()))
                || node.getOutputs().stream().anyMatch(SnapshotContextRuntime::usesContext);
    }

    @Override
    public void close() {
        if (originalContextProviders != null) {
            ContextManager.clearAll();
            ContextManager.reinitialize();
            ContextManager.register(originalContextProviders);
            ContextManager.activateContextSnapshot(originalContext);
        }
    }
}
