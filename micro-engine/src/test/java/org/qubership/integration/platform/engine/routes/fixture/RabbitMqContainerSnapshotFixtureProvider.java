package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.context.propagation.core.ContextProvider;
import com.netcracker.cloud.context.propagation.core.contexts.SerializableDataContext;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextProvider;
import com.netcracker.cloud.framework.contexts.xversion.XVersionProvider;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.MetricsCollector;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.spi.Registry;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.http.HttpHeaders;
import org.qubership.integration.platform.engine.camel.components.rabbitmq.SpringRabbitMQCustomComponent;
import org.qubership.integration.platform.engine.camel.components.rabbitmq.SpringRabbitMQCustomEndpoint;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class RabbitMqContainerSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "rabbitmq-container";
    private static final String RABBITMQ_COMPONENT_NAME = "rabbitmq-custom";
    private static final String RABBITMQ_IMAGE = "rabbitmq:3.13-alpine";
    private static final String METRICS_COLLECTOR_PARAMETER = "metricsCollector";
    private static final String USERNAME = "snapshot";
    private static final String PASSWORD = "snapshot";
    private static final int AMQP_PORT = 5672;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        return new RabbitMqContainerSnapshotFixture(deploymentId, bindings);
    }

    private static final class RabbitMqContainerSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Map<String, RabbitMqFixtureRuntime> runtimes = new LinkedHashMap<>();
        private final Map<String, String> queuesByVhost = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> initializedContexts = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Object>> restoredContexts = new ConcurrentHashMap<>();
        private List<ContextProvider<?>> originalContextProviders;
        private Map<String, Object> originalContext;
        private CamelExchangeContextPropagation contextPropagation;
        private GenericContainer<?> container;
        private RabbitMqSnapshotBroker broker;
        private RabbitMqSnapshotMaas maas;

        private RabbitMqContainerSnapshotFixture(String deploymentId, List<SnapshotFixtureBinding> bindings) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            for (SnapshotFixtureBinding binding : this.bindings) {
                for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                    SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
                    if (expectation == null || expectation.getDestination() == null || expectation.getMethod() != null
                            || expectation.getPath() != null || expectation.getQuery() != null) {
                        throw new IllegalArgumentException("RabbitMQ fixtures require a destination and message expectations.");
                    }
                    RabbitMqSnapshotRecords.responseProperties(interaction.getResponse());
                }
                runtimes.put(binding.definition().getId(), new RabbitMqFixtureRuntime(binding));
            }
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            container = new GenericContainer<>(DockerImageName.parse(RABBITMQ_IMAGE))
                    .withExposedPorts(AMQP_PORT)
                    .withEnv("RABBITMQ_DEFAULT_USER", USERNAME)
                    .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                    .withStartupTimeout(STARTUP_TIMEOUT);
            container.start();

            broker = new RabbitMqSnapshotBroker(container);
        }

        @Override
        public void beforeRouteLoad(CamelContext camelContext) throws Exception {
            if (RabbitMqSnapshotMaas.usesMaas(bindings)) {
                maas = new RabbitMqSnapshotMaas(bindings);
                broker.ensureAccount(RabbitMqSnapshotMaas.USERNAME, RabbitMqSnapshotMaas.PASSWORD, RabbitMqSnapshotMaas.VHOST);
                maas.beforeRouteLoad(container.getHost(), container.getMappedPort(AMQP_PORT));
            }
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            originalContext = ContextManager.createContextSnapshot();
            originalContextProviders = List.copyOf(ContextManager.getContextProviders());
            if (originalContextProviders.stream()
                    .noneMatch(provider -> XRequestIdContextProvider.X_REQUEST_ID_CONTEXT_NAME.equals(provider.contextName()))) {
                // Maven disables context-provider discovery for the component suite.
                ContextManager.register(List.of(new XRequestIdContextProvider()));
            }
            if (originalContextProviders.stream().noneMatch(provider -> XVersionProvider.CONTEXT_NAME.equals(provider.contextName()))) {
                ContextManager.register(List.of(new XVersionProvider()));
            }
            ContextManager.register(List.of(new RabbitMqSnapshotContextProvider()));
            contextPropagation = registerRuntimeBeans(camelContext);
            if (camelContext.getComponent(RABBITMQ_COMPONENT_NAME, false) == null) {
                camelContext.addComponent(RABBITMQ_COMPONENT_NAME, new SpringRabbitMQCustomComponent());
            }
            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            for (SnapshotFixtureBinding binding : bindings) {
                RabbitMqProducerNode producerNode = findProducerNode(routes, binding.definition());
                RabbitMqEndpointUri endpointUri = RabbitMqEndpointUri.parse(
                        producerNode.definition().getUri(), binding.definition().getId());
                String rewrittenUri;
                if (maas == null) {
                    rewrittenUri = endpointUri.withContainerConnection(container.getHost(), container.getMappedPort(AMQP_PORT));
                } else {
                    maas.verifyResolvedEndpoint(endpointUri.parameters());
                    rewrittenUri = producerNode.definition().getUri();
                }
                producerNode.definition().setUri(rewrittenUri);
                requireMetricsCollector(camelContext, endpointUri, binding.definition().getNodeId());
                RabbitMqFixtureRuntime runtime = runtimes.get(binding.definition().getId());
                runtime.vhost = endpointUri.parameters().getOrDefault("vhost", "/");
                broker.ensureAccount(endpointUri.parameters().getOrDefault("username", USERNAME),
                        endpointUri.parameters().getOrDefault("password", PASSWORD), runtime.vhost);
                bindQueue(endpointUri, binding, runtime.vhost);
                runtime.routingKey = endpointUri.routingKey();
                SpringRabbitMQCustomEndpoint endpoint = camelContext.getEndpoint(rewrittenUri, SpringRabbitMQCustomEndpoint.class);
                runtime.connectionFactory = (CachingConnectionFactory) endpoint.getConnectionFactory();
                broker.observe(runtime.connectionFactory);
                bindingsByRoute.computeIfAbsent(producerNode.route(), ignored -> new ArrayList<>()).add(binding);
            }
            Processor restoreProcessor = camelContext.getRegistry().lookupByNameAndType("contextRestoreProcessor", Processor.class);
            Processor observedRestore = exchange -> {
                restoreProcessor.process(exchange);
                restoredContexts.put(exchange.getExchangeId(), snapshotContextHeaders());
            };
            SnapshotFixtureRouteScope.bind(camelContext, List.copyOf(bindingsByRoute.keySet()), "rabbitmq:" + deploymentId,
                    Map.of("contextRestoreProcessor", observedRestore));
            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    advice.weaveAddFirst().process(exchange -> initializedContexts.put(exchange.getExchangeId(),
                            initializeRequestContext(contextPropagation, exchange)));
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        advice.weaveById(binding.definition().getNodeId()).before().process(exchange ->
                                runtimes.get(binding.definition().getId()).preparedRequests.add(
                                        new PreparedRequest(immutableMap(exchange.getProperties()),
                                                immutableMap(exchange.getMessage().getHeaders()), exchange)));
                    }
                });
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            for (RabbitMqFixtureRuntime runtime : runtimes.values()) {
                runtime.preparedBaseline = runtime.preparedRequests.size();
                SnapshotFixtureInteraction interaction = runtime.binding.interaction(invocation.getId());
                if (RabbitMqSnapshotRecords.expectedSendCount(interaction, invocation.getRepeat()) > 0) {
                    broker.beforeInvocation(RabbitMqSnapshotRecords.responseProperties(interaction.getResponse()),
                            List.of(runtime.connectionFactory));
                }
            }
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            Map<String, List<RabbitMqSnapshotRecords.ExpectedRecord>> expectedByVhost = new LinkedHashMap<>();
            for (RabbitMqFixtureRuntime runtime : runtimes.values()) {
                expectedByVhost.computeIfAbsent(runtime.vhost, ignored -> new ArrayList<>())
                        .addAll(runtime.verifyInvocation(invocation, initializedContexts, restoredContexts));
                SnapshotFixtureInteraction interaction = runtime.binding.interaction(invocation.getId());
                if (interaction != null) {
                    Map<String, Object> properties = RabbitMqSnapshotRecords.responseProperties(interaction.getResponse());
                    broker.verifyInvocation(properties);
                }
            }
            for (Map.Entry<String, String> queue : queuesByVhost.entrySet()) {
                List<RabbitMqSnapshotRecords.ExpectedRecord> expected = expectedByVhost.get(queue.getKey());
                if (!broker.isRunning()) {
                    assertEquals(0, expected.size(), "A stopped RabbitMQ broker cannot publish records.");
                    continue;
                }
                try (Connection inspector = openConnection(queue.getKey())) {
                    List<GetResponse> records = RabbitMqSnapshotRecords.read(inspector, queue.getValue(), expected.size());
                    RabbitMqSnapshotRecords.assertRecords(expected, records,
                            "RabbitMQ invocation '" + invocation.getId() + "' in vhost '" + queue.getKey() + "'");
                }
            }
        }

        @Override
        public void verify() {
            if (maas != null) {
                maas.verifyLookups();
            }
            for (Map.Entry<String, String> queue : queuesByVhost.entrySet()) {
                try (Connection inspector = openConnection(queue.getKey())) {
                    RabbitMqSnapshotRecords.assertRecords(List.of(), RabbitMqSnapshotRecords.read(inspector, queue.getValue(), 0),
                            "RabbitMQ vhost '" + queue.getKey() + "' after its final invocation");
                } catch (IOException | TimeoutException exception) {
                    throw new IllegalStateException("Cannot verify RabbitMQ records.", exception);
                }
            }
        }

        @Override
        public void close() throws Exception {
            try {
                if (container != null) {
                    container.stop();
                }
            } finally {
                if (originalContextProviders != null) {
                    ContextManager.clearAll();
                    ContextManager.reinitialize();
                    ContextManager.register(originalContextProviders);
                    ContextManager.activateContextSnapshot(originalContext);
                }
            }
        }

        private Connection openConnection(String vhost) throws IOException, TimeoutException {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(container.getHost());
            factory.setPort(container.getMappedPort(AMQP_PORT));
            factory.setUsername(USERNAME);
            factory.setPassword(PASSWORD);
            factory.setVirtualHost(vhost);
            factory.setAutomaticRecoveryEnabled(false);
            return factory.newConnection();
        }

        private void bindQueue(RabbitMqEndpointUri endpointUri, SnapshotFixtureBinding binding, String vhost)
                throws IOException, TimeoutException {
            try (Connection inspector = openConnection(vhost); Channel channel = inspector.createChannel()) {
                channel.exchangeDeclare(endpointUri.exchange(), endpointUri.exchangeType(), true);
                String queue = queuesByVhost.get(vhost);
                if (queue == null) {
                    queue = channel.queueDeclare("snapshot-" + UUID.randomUUID(), true, false, false, Map.of()).getQueue();
                    queuesByVhost.put(vhost, queue);
                }
                channel.queueBind(queue, endpointUri.exchange(), endpointUri.routingKey());
                for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
                    Object configured = RabbitMqSnapshotRecords.responseProperties(interaction.getResponse()).get("bindings");
                    if (configured == null) {
                        continue;
                    }
                    if (!(configured instanceof List<?> additionalBindings)) {
                        throw new IllegalArgumentException("RabbitMQ bindings must be a list.");
                    }
                    for (Object value : additionalBindings) {
                        if (!(value instanceof Map<?, ?> fields) || !(fields.get("exchange") instanceof String exchange)
                                || exchange.isBlank() || !(fields.get("routingKey") instanceof String routingKey)) {
                            throw new IllegalArgumentException("RabbitMQ bindings require an exchange and a routingKey.");
                        }
                        channel.exchangeDeclare(exchange, "direct", true);
                        channel.queueBind(queue, exchange, routingKey);
                    }
                }
            }
        }
    }

    private static final class RabbitMqFixtureRuntime {
        private final SnapshotFixtureBinding binding;
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private String routingKey;
        private String vhost;
        private CachingConnectionFactory connectionFactory;
        private int preparedBaseline;

        private RabbitMqFixtureRuntime(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private List<RabbitMqSnapshotRecords.ExpectedRecord> verifyInvocation(SnapshotScenarioInvocation invocation,
                Map<String, Map<String, Object>> initializedContexts, Map<String, Map<String, Object>> restoredContexts) {
            SnapshotFixtureInteraction interaction = binding.interaction(invocation.getId());
            List<PreparedRequest> prepared = preparedRequests.subList(preparedBaseline, preparedRequests.size());
            RabbitMqSnapshotRecords.assertPreparedCount(interaction, invocation.getRepeat(), prepared.size(),
                    "RabbitMQ fixture '" + binding.definition().getId() + "' invocation '" + invocation.getId() + "'");
            if (interaction == null) {
                return List.of();
            }
            Object incomingRequestId = new CaseInsensitiveMap<>(invocation.getHeaders()).get("X-Request-Id");
            for (PreparedRequest request : prepared) {
                String exchangeId = request.exchange().getExchangeId();
                Map<String, Object> initializedContext = initializedContexts.get(exchangeId);
                assertNotNull(initializedContext, "RabbitMQ did not initialize request context for " + invocation.getId());
                Object initializedRequestId = new CaseInsensitiveMap<>(initializedContext).get("X-Request-Id");
                if (incomingRequestId == null) {
                    assertTrue(initializedRequestId instanceof String id && !id.isBlank(),
                            "RabbitMQ did not generate a request ID for " + invocation.getId());
                    assertTrue(initializedContexts.entrySet().stream()
                                    .filter(entry -> !entry.getKey().equals(exchangeId))
                                    .noneMatch(entry -> initializedRequestId.equals(
                                            new CaseInsensitiveMap<>(entry.getValue()).get("X-Request-Id"))),
                            "RabbitMQ reused another exchange's request ID for " + invocation.getId());
                } else {
                    assertEquals(incomingRequestId, initializedRequestId,
                            "RabbitMQ did not initialize the incoming request ID for " + invocation.getId());
                }
                Map<String, Object> restoredContext = restoredContexts.get(exchangeId);
                assertNotNull(restoredContext, "RabbitMQ did not execute the context restore processor for " + invocation.getId());
                assertEquals(initializedContext, restoredContext,
                        "RabbitMQ did not restore the incoming request context for " + invocation.getId());
                RabbitMqSnapshotRecords.assertPreparedHeaders(interaction, request.headers());
                SnapshotValueAssertions.assertMapValues(interaction.getExpectedRequest().getProperties(), request.properties(),
                        "RabbitMQ prepared an unexpected exchange property");
            }
            return RabbitMqSnapshotRecords.expectedRecords(interaction, routingKey, prepared.stream()
                    .map(request -> initializedContexts.get(request.exchange().getExchangeId())).toList());
        }
    }

    private static CamelExchangeContextPropagation registerRuntimeBeans(CamelContext camelContext) {
        Registry registry = camelContext.getRegistry();
        CDI<Object> container = CDI.current();
        for (String name : List.of("contextPropagationProcessor", "contextRestoreProcessor", "rabbitMqSenderProcessor",
                "messagingXHeadersPropagationProcessor", "messagingXHeadersPropagationRestoreProcessor")) {
            Instance<Processor> processors = container.select(Processor.class, NamedLiteral.of(name));
            if (!processors.isResolvable()) {
                throw new IllegalStateException("RabbitMQ container fixture cannot resolve CDI processor '" + name + "'.");
            }
            registry.bind(name, Processor.class, processors.get());
        }
        Instance<CamelExchangeContextPropagation> propagation = container.select(CamelExchangeContextPropagation.class);
        if (!propagation.isResolvable()) {
            throw new IllegalStateException("RabbitMQ container fixture cannot resolve CDI context propagation.");
        }
        return propagation.get();
    }

    private static Map<String, Object> initializeRequestContext(CamelExchangeContextPropagation propagation, Exchange exchange) {
        Map<String, Object> headers = exchange.getMessage().getHeaders();
        propagation.initRequestContext(headers);
        Object authorization = exchange.getMessage().getHeader(HttpHeaders.AUTHORIZATION);
        propagation.removeContextHeaders(headers);
        if (authorization != null) {
            exchange.getMessage().setHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        exchange.setProperty(REQUEST_CONTEXT_PROPAGATION_SNAPSHOT, propagation.createContextSnapshot());
        return snapshotContextHeaders();
    }

    private static Map<String, Object> snapshotContextHeaders() {
        Map<String, Object> headers = new CaseInsensitiveMap<>();
        ContextManager.getAll().stream().filter(SerializableDataContext.class::isInstance)
                .map(SerializableDataContext.class::cast)
                .forEach(context -> headers.putAll(context.getSerializableContextData()));
        return immutableMap(headers);
    }

    private static void requireMetricsCollector(CamelContext camelContext, RabbitMqEndpointUri endpointUri, String nodeId) {
        String reference = endpointUri.parameters().get(METRICS_COLLECTOR_PARAMETER);
        if (reference == null || !reference.startsWith("#")) {
            throw new IllegalArgumentException("RabbitMQ container fixture node '" + nodeId
                    + "' does not define a metrics collector reference.");
        }
        String beanName = reference.substring(1);
        if (camelContext.getRegistry().lookupByNameAndType(beanName, MetricsCollector.class) == null) {
            throw new IllegalStateException("Micro-engine snapshot is missing RabbitMQ metrics collector '" + beanName + "'.");
        }
    }

    private static RabbitMqProducerNode findProducerNode(List<RouteDefinition> routes, SnapshotFixtureDefinition definition) {
        List<RabbitMqProducerNode> matchingNodes = new ArrayList<>();
        for (SnapshotRouteNodes.NodeMatch match : SnapshotRouteNodes.findById(routes, definition.getNodeId())) {
            if (!(match.definition() instanceof ToDynamicDefinition toDynamicDefinition)) {
                throw new IllegalArgumentException("RabbitMQ container fixture '" + definition.getId() + "' node '"
                        + definition.getNodeId() + "' is not a dynamic endpoint.");
            }
            matchingNodes.add(new RabbitMqProducerNode(match.route(), toDynamicDefinition));
        }
        return SnapshotRouteNodes.requireSingle(matchingNodes,
                "RabbitMQ container fixture '" + definition.getId() + "' expected one dynamic endpoint node '"
                        + definition.getNodeId() + "' in deployment '" + definition.getDeploymentId() + "'");
    }

    private record RabbitMqProducerNode(RouteDefinition route, ToDynamicDefinition definition) {
    }

    private record PreparedRequest(Map<String, Object> properties, Map<String, Object> headers, Exchange exchange) {
    }

    private record RabbitMqEndpointUri(
            String endpoint,
            String exchange,
            String routingKey,
            String exchangeType,
            Map<String, String> parameters
    ) {
        private static RabbitMqEndpointUri parse(String uri, String fixtureId) {
            int queryStart = uri.indexOf('?');
            String endpoint = queryStart < 0 ? uri : uri.substring(0, queryStart);
            String query = queryStart < 0 ? "" : uri.substring(queryStart + 1);
            int schemeEnd = endpoint.indexOf(':');
            if (schemeEnd < 0
                    || !RABBITMQ_COMPONENT_NAME.equals(endpoint.substring(0, schemeEnd))) {
                throw new IllegalArgumentException(
                        "RabbitMQ container fixture '" + fixtureId
                                + "' must target a rabbitmq-custom endpoint, but found '" + uri + "'."
                );
            }

            String exchange = endpoint.substring(schemeEnd + 1);
            requireStaticValue(exchange, fixtureId, "exchange");

            if (query.contains("RAW(")) {
                throw new IllegalArgumentException(
                        "RabbitMQ container fixture '" + fixtureId
                                + "' does not support RAW endpoint parameters."
                );
            }
            Map<String, String> parameters = SnapshotEndpointParameters.parse(query, name ->
                    new IllegalArgumentException(
                            "RabbitMQ container fixture '" + fixtureId
                                    + "' does not support duplicate endpoint parameter '" + name + "'."
                    ));
            String routingKey = parameters.getOrDefault("routingKey", "");
            if (!routingKey.isEmpty()) {
                requireStaticValue(routingKey, fixtureId, "routing key");
            }
            String exchangeType = parameters.getOrDefault("exchangeType", "direct")
                    .toLowerCase(Locale.ROOT);
            return new RabbitMqEndpointUri(
                    endpoint,
                    exchange,
                    routingKey,
                    exchangeType,
                    parameters
            );
        }

        private String withContainerConnection(String host, int port) {
            Map<String, String> containerParameters = new LinkedHashMap<>(parameters);
            containerParameters.remove("sslProtocol");
            containerParameters.remove("trustManager");
            containerParameters.put("addresses", host + ':' + port);
            containerParameters.putIfAbsent("username", USERNAME);
            containerParameters.putIfAbsent("password", PASSWORD);
            containerParameters.putIfAbsent("vhost", "/");
            containerParameters.put("connectionTimeout", "2000");

            String query = containerParameters.entrySet().stream()
                    .map(entry -> entry.getKey() + '=' + entry.getValue())
                    .collect(Collectors.joining("&"));
            return endpoint + '?' + query;
        }

        private static void requireStaticValue(
                String value,
                String fixtureId,
                String valueName
        ) {
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        "RabbitMQ container fixture '" + fixtureId
                                + "' endpoint does not define an " + valueName + "."
                );
            }
            if (value.contains("${") || value.contains("{{")) {
                throw new IllegalArgumentException(
                        "RabbitMQ container fixture '" + fixtureId
                                + "' requires a static " + valueName + "."
                );
            }
        }
    }
}
