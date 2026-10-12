package org.qubership.integration.platform.engine.routes.fixture;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublishResponse;
import com.google.pubsub.v1.PubsubMessage;
import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.context.propagation.core.ContextProvider;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextProvider;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.google.pubsub.serializer.GooglePubsubSerializer;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.spi.Registry;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.http.HttpHeaders;
import org.qubership.integration.platform.engine.camel.components.pubsub.CustomGooglePubSubComponent;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.service.debugger.util.CustomGooglePubSubSerializer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class PubSubGrpcSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "pubsub-grpc";
    private static final String COMPONENT_NAME = "google-pubsub";
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String ENDPOINT_PREFIX = COMPONENT_NAME + ':';
    private static final String SERVICE_NAME = "google.pubsub.v1.Publisher";
    private static final String SERVICE_ACCOUNT_KEY_PARAMETER = "serviceAccountKey";
    private static final String BASE64_RESOURCE_PREFIX = "base64:";
    private static final long SERVER_TERMINATION_TIMEOUT_SECONDS = 10;
    private static final byte[] CREDENTIAL_VALIDATION_PAYLOAD =
            "snapshot-credential-validation".getBytes(StandardCharsets.UTF_8);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SnapshotFixture create(String deploymentId, List<SnapshotFixtureBinding> bindings) {
        return new PubSubGrpcSnapshotFixture(deploymentId, bindings);
    }

    private static final class PubSubGrpcSnapshotFixture implements SnapshotFixture {
        private final String deploymentId;
        private final List<SnapshotFixtureBinding> bindings;
        private final Map<String, PubSubFixtureRuntime> runtimesByFixtureId = new LinkedHashMap<>();
        private final Map<String, PubSubFixtureRuntime> runtimesByTopic = new LinkedHashMap<>();
        private final List<PublishRequest> receivedRequests = new CopyOnWriteArrayList<>();
        private final List<PublishedResponse> publishedResponses = new CopyOnWriteArrayList<>();
        private List<ContextProvider<?>> originalContextProviders;
        private Map<String, Object> originalContext;
        private CamelExchangeContextPropagation contextPropagation;
        private PubSubOAuthServer oauth;
        private PubSubEmulator emulator;
        private PubSubConnectionProxy connectionProxy;
        private int tokenRequestBaseline;
        private Server server;

        private PubSubGrpcSnapshotFixture(
                String deploymentId,
                List<SnapshotFixtureBinding> bindings
        ) {
            this.deploymentId = deploymentId;
            this.bindings = List.copyOf(bindings);
            for (SnapshotFixtureBinding binding : this.bindings) {
                validateBinding(binding);
                runtimesByFixtureId.put(
                        binding.definition().getId(),
                        new PubSubFixtureRuntime(binding)
                );
            }
        }

        @Override
        public String getDeploymentId() {
            return deploymentId;
        }

        @Override
        public void start() throws Exception {
            oauth = new PubSubOAuthServer();
            oauth.start();
            emulator = new PubSubEmulator();
            emulator.start();
            ServerServiceDefinition publisherService = ServerServiceDefinition.builder(SERVICE_NAME)
                    .addMethod(publishMethod(), ServerCalls.asyncUnaryCall(this::publish))
                    .build();
            server = oauth.configureTls(NettyServerBuilder.forAddress(new InetSocketAddress(LOOPBACK_HOST, 0)))
                    .intercept(oauth.interceptor())
                    .addService(publisherService)
                    .build()
                    .start();
            if (bindings.stream().flatMap(binding -> binding.interactionsByInvocationId().values().stream())
                    .anyMatch(interaction -> responsePlan(interaction.getResponse(), "connection").disconnect())) {
                connectionProxy = new PubSubConnectionProxy();
                connectionProxy.start(LOOPBACK_HOST, server.getPort());
            }
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> deploymentRoutes) throws Exception {
            if (ContextManager.getContextProviders().stream()
                    .noneMatch(provider -> XRequestIdContextProvider.X_REQUEST_ID_CONTEXT_NAME.equals(provider.contextName()))) {
                originalContextProviders = List.copyOf(ContextManager.getContextProviders());
                originalContext = ContextManager.createContextSnapshot();
                // Maven disables provider discovery, so register the production provider for these scenarios.
                ContextManager.register(List.of(new XRequestIdContextProvider()));
            }
            contextPropagation = registerRuntimeBeans(camelContext);

            String componentName = "snapshot-pubsub-" + server.getPort();
            CustomGooglePubSubComponent component = new CustomGooglePubSubComponent();
            component.setAuthenticate(true);
            camelContext.addComponent(componentName, component);

            Map<RouteDefinition, List<SnapshotFixtureBinding>> bindingsByRoute = new LinkedHashMap<>();
            Set<String> topics = new LinkedHashSet<>();
            for (SnapshotFixtureBinding binding : bindings) {
                PubSubProducerNode producerNode = findProducerNode(
                        deploymentRoutes,
                        binding.definition()
                );
                PubSubEndpointUri endpointUri = PubSubEndpointUri.parse(
                        producerNode.endpoint().getUri(),
                        binding.definition().getId()
                );
                validateServiceAccountKey(endpointUri, binding.definition().getId());
                producerNode.endpoint().setUri(endpointUri.withLocalPublisher(
                        componentName,
                        oauth.credentialsResource(endpointUri.parameters().get(SERVICE_ACCOUNT_KEY_PARAMETER)),
                        "localhost:" + (connectionProxy == null ? server.getPort() : connectionProxy.port())
                ));

                String topic = endpointUri.topic();
                if (!topics.add(topic)) {
                    throw new IllegalArgumentException(
                            "Pub/Sub gRPC fixtures in deployment '" + deploymentId
                                    + "' cannot share topic '" + topic + "'."
                    );
                }
                PubSubFixtureRuntime runtime = runtimesByFixtureId.get(binding.definition().getId());
                runtime.configure(endpointUri);
                emulator.createTopic(topic);
                runtimesByTopic.put(topic, runtime);
                bindingsByRoute.computeIfAbsent(producerNode.route(), ignored -> new ArrayList<>())
                        .add(binding);
            }

            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    advice.weaveAddFirst()
                            .process(exchange -> initializeRequestContext(contextPropagation, exchange));
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        advice.weaveById(binding.definition().getNodeId())
                                .before()
                                .process(exchange -> capturePreparedRequest(binding, exchange));
                    }
                });
            }
        }

        @Override
        public void beforeInvocation(SnapshotScenarioInvocation invocation) throws Exception {
            tokenRequestBaseline = oauth.tokenRequestCount();
            for (PubSubFixtureRuntime runtime : runtimesByFixtureId.values()) {
                runtime.beforeInvocation(invocation.getId(), receivedRequests.size(), publishedResponses.size());
                if ("delete".equals(runtime.responsePlan.topicAction())) {
                    emulator.deleteTopic(runtime.endpointUri.topic());
                } else if ("recreate".equals(runtime.responsePlan.topicAction())) {
                    runtime.verifyDelivery(emulator);
                    runtime.expectedDeliveries.clear();
                    emulator.createTopic(runtime.endpointUri.topic());
                }
            }
            oauth.beforeInvocation(runtimesByFixtureId.values().stream()
                    .map(runtime -> runtime.responsePlan.oauthError())
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null),
                    runtimesByFixtureId.values().stream().anyMatch(runtime -> runtime.responsePlan.shortLivedAccessToken()),
                    runtimesByFixtureId.values().stream().anyMatch(runtime -> runtime.responsePlan.expireAccessToken()));
        }

        @Override
        public void verifyInvocation(SnapshotScenarioInvocation invocation) {
            oauth.verifyInvocation();
            if (runtimesByFixtureId.values().stream().anyMatch(runtime -> runtime.responsePlan.disconnect())) {
                connectionProxy.verifyOutage();
            }
            Map<String, Object> incomingHeaders = new CaseInsensitiveMap<>(invocation.getHeaders());
            Object incomingRequestId = incomingHeaders.get("X-Request-Id");
            if (incomingRequestId != null) {
                assertEquals(
                        incomingRequestId,
                        contextPropagation.getHeadersForCurrentContext().get("X-Request-Id"),
                        "Pub/Sub invocation '" + invocation.getId() + "' did not restore the incoming request context."
                );
            }
            for (PubSubFixtureRuntime runtime : runtimesByFixtureId.values()) {
                runtime.verify(invocation, receivedRequests, publishedResponses);
            }
            List<Integer> expectedTokenRequests = runtimesByFixtureId.values().stream()
                    .map(runtime -> runtime.responsePlan.expectedTokenRequests())
                    .filter(Objects::nonNull).toList();
            if (!expectedTokenRequests.isEmpty()) {
                assertEquals(expectedTokenRequests.stream().mapToInt(Integer::intValue).sum(),
                        oauth.tokenRequestCount() - tokenRequestBaseline,
                        "Pub/Sub invocation '" + invocation.getId() + "' made an unexpected number of OAuth token requests.");
            }
        }

        @Override
        public void verify() {
            oauth.verify();
            int expectedRequestCount = runtimesByFixtureId.values().stream()
                    .mapToInt(PubSubFixtureRuntime::expectedRequestCount)
                    .sum();
            assertEquals(
                    expectedRequestCount,
                    receivedRequests.size(),
                    "Pub/Sub gRPC fixture received an unexpected total number of requests."
            );
            for (PubSubFixtureRuntime runtime : runtimesByFixtureId.values()) {
                runtime.verifyDelivery(emulator);
            }
        }

        @Override
        public void close() throws Exception {
            try (PubSubOAuthServer oauthToClose = oauth; PubSubEmulator emulatorToClose = emulator;
                    PubSubConnectionProxy proxyToClose = connectionProxy) {
                stopServer();
            } finally {
                if (originalContextProviders != null) {
                    ContextManager.clearAll();
                    ContextManager.reinitialize();
                    ContextManager.register(originalContextProviders);
                    ContextManager.activateContextSnapshot(originalContext);
                    originalContextProviders = null;
                }
            }
        }

        private void stopServer() {
            if (server == null) {
                return;
            }

            Server serverToStop = server;
            server = null;
            serverToStop.shutdown();
            try {
                if (!serverToStop.awaitTermination(SERVER_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    serverToStop.shutdownNow();
                    assertTrue(
                            serverToStop.awaitTermination(SERVER_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                            "Pub/Sub gRPC fixture server did not terminate."
                    );
                }
            } catch (InterruptedException exception) {
                serverToStop.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while stopping the Pub/Sub gRPC fixture server.",
                        exception
                );
            }
        }

        private void publish(
                PublishRequest request,
                StreamObserver<PublishResponse> responseObserver
        ) {
            receivedRequests.add(request);
            PubSubFixtureRuntime runtime = runtimesByTopic.get(request.getTopic());
            Status failure = runtime == null ? null : runtime.nextFailure();
            if (failure != null) {
                responseObserver.onError(failure.asRuntimeException());
                return;
            }
            try {
                PublishResponse response = emulator.publish(request);
                boolean responseLost = runtime != null && runtime.loseNextResponse();
                publishedResponses.add(new PublishedResponse(request.getTopic(), response, responseLost));
                if (responseLost) {
                    responseObserver.onError(Status.UNAVAILABLE
                            .withDescription("Publish response lost after acceptance").asRuntimeException());
                    return;
                }
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception exception) {
                responseObserver.onError(Status.fromThrowable(exception).asRuntimeException());
            }
        }

        private void capturePreparedRequest(
                SnapshotFixtureBinding binding,
                Exchange exchange
        ) {
            Object body = exchange.getMessage().getBody();
            if (body instanceof Iterable<?>) {
                throw new IllegalArgumentException(
                        "Pub/Sub gRPC fixture '" + binding.definition().getId()
                                + "' does not support iterable request bodies."
                );
            }
            PubSubFixtureRuntime runtime = runtimesByFixtureId.get(binding.definition().getId());
            if (runtime.responsePlan.disconnect()) {
                connectionProxy.disconnectOnNextClientWrite();
            }
            runtime.addPreparedRequest(
                    new PreparedRequest(immutableMap(exchange.getProperties()), exchange)
            );
        }
    }

    private static final class PubSubFixtureRuntime {
        private final SnapshotFixtureBinding binding;
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private final List<ExpectedDelivery> expectedDeliveries = new ArrayList<>();
        private final AtomicInteger remainingFailures = new AtomicInteger();
        private final AtomicInteger remainingLostResponses = new AtomicInteger();
        private PubSubEndpointUri endpointUri;
        private volatile PubSubResponsePlan responsePlan;
        private int receivedRequestBaseline;
        private int preparedRequestBaseline;
        private int publishedResponseBaseline;

        private PubSubFixtureRuntime(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void configure(PubSubEndpointUri endpointUri) {
            this.endpointUri = endpointUri;
        }

        private void addPreparedRequest(PreparedRequest preparedRequest) {
            preparedRequests.add(preparedRequest);
        }

        private void beforeInvocation(String invocationId, int receivedRequestCount, int publishedResponseCount) {
            SnapshotFixtureInteraction interaction = binding.interaction(invocationId);
            responsePlan = responsePlan(interaction == null ? null : interaction.getResponse(), binding.definition().getId());
            remainingFailures.set(responsePlan.failureCount());
            remainingLostResponses.set(responsePlan.lostPublishResponseCount());
            receivedRequestBaseline = receivedRequestCount;
            preparedRequestBaseline = preparedRequests.size();
            publishedResponseBaseline = publishedResponseCount;
        }

        private Status nextFailure() {
            if (responsePlan.failure() == null) {
                return null;
            }
            return remainingFailures.getAndUpdate(count -> count > 0 ? count - 1 : count) == 0
                    ? null : responsePlan.failure();
        }

        private boolean loseNextResponse() {
            return remainingLostResponses.getAndUpdate(count -> count > 0 ? count - 1 : count) > 0;
        }

        private int expectedRequestCount() {
            return binding.interactionsByInvocationId().values().stream()
                    .mapToInt(interaction -> interaction.getExpectedRequest().getCount())
                    .sum();
        }

        private void verify(
                SnapshotScenarioInvocation invocation,
                List<PublishRequest> allRequests,
                List<PublishedResponse> allResponses
        ) {
            String invocationId = invocation.getId();
            SnapshotFixtureInteraction interaction = binding.interaction(invocationId);
            SnapshotFixtureRequestExpectation expectation = interaction == null
                    ? null : interaction.getExpectedRequest();
            String fixtureId = binding.definition().getId();
            List<PublishRequest> requests = allRequests.subList(receivedRequestBaseline, allRequests.size()).stream()
                    .filter(request -> endpointUri.topic().equals(request.getTopic()))
                    .toList();
            List<PreparedRequest> invocationPreparedRequests = List.copyOf(
                    preparedRequests.subList(preparedRequestBaseline, preparedRequests.size())
            );
            List<PublishedResponse> acceptedResponses = allResponses.subList(publishedResponseBaseline, allResponses.size()).stream()
                    .filter(response -> endpointUri.topic().equals(response.topic()))
                    .toList();
            List<String> messageIds = acceptedResponses.stream()
                    .filter(response -> !response.responseLost())
                    .flatMap(response -> response.response().getMessageIdsList().stream()).toList();
            assertEquals(
                    expectation == null ? 0 : expectation.getCount(),
                    requests.size(),
                    () -> "Pub/Sub gRPC fixture '" + fixtureId + "' invocation '" + invocationId
                            + "' received an unexpected number of requests."
            );
            int expectedPreparedCount = expectation == null ? 0 : invocation.getRepeat();
            assertEquals(
                    expectedPreparedCount,
                    invocationPreparedRequests.size(),
                    () -> "Pub/Sub gRPC fixture '" + fixtureId + "' invocation '" + invocationId
                            + "' prepared an unexpected number of logical sends."
            );
            boolean publicationFailed = invocation.isExpectedFailure();
            assertEquals(publicationFailed ? 0 : expectedPreparedCount, messageIds.size(),
                    "Pub/Sub fixture returned an unexpected number of successful message IDs.");
            assertEquals(responsePlan.lostPublishResponseCount(),
                    acceptedResponses.stream().filter(PublishedResponse::responseLost).count(),
                    "Pub/Sub fixture lost an unexpected number of accepted Publish responses.");
            assertEquals(messageIds.size() + responsePlan.lostPublishResponseCount(), acceptedResponses.size(),
                    "Pub/Sub emulator accepted an unexpected number of publications.");
            for (PublishedResponse accepted : acceptedResponses) {
                assertEquals(1, accepted.response().getMessageIdsCount(),
                        "Pub/Sub emulator must return one message ID per accepted request.");
                expectedDeliveries.add(new ExpectedDelivery(expectation, accepted.response().getMessageIds(0)));
            }

            for (int index = 0; index < requests.size(); index++) {
                PublishRequest request = requests.get(index);
                assertEquals(1, request.getMessagesCount(), "Pub/Sub gRPC fixture expects one message per request.");
                verifyMessage(
                        fixtureId,
                        index + 1,
                        expectation,
                        request
                );
                if (expectedPreparedCount == 1) {
                    assertEquals(requests.getFirst(), request, "Pub/Sub retry changed the Publish request.");
                }
            }
            for (int index = 0; index < invocationPreparedRequests.size(); index++) {
                PreparedRequest preparedRequest = invocationPreparedRequests.get(index);
                SnapshotValueAssertions.assertMapValues(
                        expectation.getProperties(), preparedRequest.properties(),
                        "Pub/Sub gRPC fixture '" + fixtureId + "' has an unexpected prepared property"
                );
                assertEquals(publicationFailed ? null : messageIds.get(index),
                        preparedRequest.exchange().getMessage().getHeader("CamelGooglePubsubMessageId"),
                        "Pub/Sub sender did not return the publisher's message ID.");
            }
        }

        private void verifyDelivery(PubSubEmulator emulator) {
            List<PubsubMessage> messages = emulator.receive(endpointUri.topic(), expectedDeliveries.size());
            Map<String, List<ExpectedDelivery>> expectedByKey = new LinkedHashMap<>();
            for (ExpectedDelivery expected : expectedDeliveries) {
                String key = expected.expectation().getKey() == null ? "" : expected.expectation().getKey();
                expectedByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(expected);
            }
            Map<String, List<PubsubMessage>> actualByKey = new LinkedHashMap<>();
            for (PubsubMessage message : messages) {
                actualByKey.computeIfAbsent(message.getOrderingKey(), ignored -> new ArrayList<>()).add(message);
            }
            assertEquals(expectedByKey.keySet(), actualByKey.keySet(), "Pub/Sub subscriber received unexpected ordering keys.");
            expectedByKey.forEach((key, expected) -> {
                List<PubsubMessage> actual = actualByKey.get(key);
                assertEquals(expected.size(), actual.size(), "Pub/Sub subscriber received an unexpected number of messages for " + key);
                for (int index = 0; index < expected.size(); index++) {
                    ExpectedDelivery delivery = expected.get(index);
                    // Pub/Sub does not guarantee delivery order when the ordering key is empty.
                    PubsubMessage message = key.isEmpty()
                            ? actual.stream().filter(item -> delivery.messageId().equals(item.getMessageId())).findFirst().orElseThrow()
                            : actual.get(index);
                    String description = "Pub/Sub subscriber message " + (index + 1) + " for ordering key '" + key + "'.";
                    assertEquals(delivery.messageId(), message.getMessageId(), description);
                    if (delivery.expectation().hasBody()) {
                        verifyBody(delivery.expectation().getBody(), message.getData().toByteArray(), description);
                    }
                    SnapshotValueAssertions.assertMapValues(delivery.expectation().getHeaders(), message.getAttributesMap(), description);
                }
            });
        }

        private void verifyMessage(
                String fixtureId,
                int messageNumber,
                SnapshotFixtureRequestExpectation expectation,
                PublishRequest request
        ) {
            assertEquals(
                    expectation.getDestination(),
                    endpointUri.destination(),
                    () -> messageFailure(fixtureId, messageNumber, "destination")
            );
            assertEquals(
                    endpointUri.topic(),
                    request.getTopic(),
                    () -> messageFailure(fixtureId, messageNumber, "topic")
            );
            PubsubMessage message = request.getMessages(0);
            assertEquals(
                    expectation.getKey() == null ? "" : expectation.getKey(),
                    message.getOrderingKey(),
                    () -> messageFailure(fixtureId, messageNumber, "ordering key")
            );
            if (expectation.hasBody()) {
                verifyBody(
                        expectation.getBody(),
                        message.getData().toByteArray(),
                        messageFailure(fixtureId, messageNumber, "body")
                );
            }
            SnapshotValueAssertions.assertMapValues(
                    expectation.getHeaders(),
                    message.getAttributesMap(),
                    "Pub/Sub gRPC fixture '" + fixtureId + "' message " + messageNumber
                            + " has an unexpected attribute"
            );
        }
    }

    private static void validateBinding(SnapshotFixtureBinding binding) {
        String fixtureId = binding.definition().getId();
        for (SnapshotFixtureInteraction interaction : binding.interactionsByInvocationId().values()) {
            SnapshotFixtureRequestExpectation expectation = interaction.getExpectedRequest();
            if (expectation == null || expectation.getDestination() == null) {
                throw new IllegalArgumentException(
                        "Pub/Sub gRPC fixture '" + fixtureId + "' must define an expected request with a destination."
                );
            }
            if (expectation.getMethod() != null
                    || expectation.getPath() != null
                    || expectation.getQuery() != null) {
                throw new IllegalArgumentException(
                        "Pub/Sub gRPC fixture '" + fixtureId
                                + "' does not support HTTP method, path, or query expectations."
                );
            }
            responsePlan(interaction.getResponse(), fixtureId);
        }
    }

    private static PubSubResponsePlan responsePlan(SnapshotFixtureResponse response, String fixtureId) {
        if (response == null) {
            return new PubSubResponsePlan(null, 0, null, null, 0, false, false, null, false);
        }
        if (response.hasExplicitStatus()
                || response.getDelayMillis() != null
                || response.getBody() != null
                || !response.getHeaders().isEmpty()
                || !Set.of("grpcStatus", "grpcMessage", "grpcFailureCount", "oauthError", "expectedTokenRequests",
                        "lostPublishResponseCount", "shortLivedAccessToken", "expireAccessToken", "topicAction", "disconnect")
                        .containsAll(response.getProperties().keySet())) {
            throw new IllegalArgumentException(
                    "Pub/Sub gRPC fixture '" + fixtureId
                            + "' response contains unsupported fields."
            );
        }
        Map<String, Object> properties = response.getProperties();
        String statusName = stringProperty(properties, "grpcStatus");
        String message = stringProperty(properties, "grpcMessage");
        Status failure = statusName == null ? null : Status.Code.valueOf(statusName).toStatus().withDescription(message);
        Integer failureCount = integerProperty(properties, "grpcFailureCount");
        if (failure != null && failure.isOk()) {
            throw new IllegalArgumentException("Pub/Sub grpcStatus must describe a failure.");
        }
        if (failureCount != null && (failure == null || failureCount <= 0)) {
            throw new IllegalArgumentException("Pub/Sub grpcFailureCount requires a grpcStatus and a positive count.");
        }
        if (failure != null && Set.of(Status.Code.ABORTED, Status.Code.CANCELLED, Status.Code.DEADLINE_EXCEEDED,
                Status.Code.INTERNAL, Status.Code.RESOURCE_EXHAUSTED, Status.Code.UNKNOWN, Status.Code.UNAVAILABLE)
                .contains(failure.getCode()) && failureCount == null) {
            throw new IllegalArgumentException("Retryable Pub/Sub failures require grpcFailureCount so publication can complete.");
        }
        String oauthError = stringProperty(properties, "oauthError");
        if (oauthError != null && !"invalid_grant".equals(oauthError)) {
            throw new IllegalArgumentException("Pub/Sub oauthError supports only invalid_grant.");
        }
        Integer expectedTokenRequests = integerProperty(properties, "expectedTokenRequests");
        if (expectedTokenRequests != null && expectedTokenRequests < 0) {
            throw new IllegalArgumentException("Pub/Sub expectedTokenRequests cannot be negative.");
        }
        Integer lostResponseCount = integerProperty(properties, "lostPublishResponseCount");
        if (lostResponseCount != null && (lostResponseCount <= 0 || oauthError != null
                || (failure != null && failureCount == null))) {
            throw new IllegalArgumentException("Pub/Sub lostPublishResponseCount requires a positive count and a recoverable publication.");
        }
        String topicAction = stringProperty(properties, "topicAction");
        if (topicAction != null && !Set.of("delete", "recreate").contains(topicAction)) {
            throw new IllegalArgumentException("Pub/Sub topicAction supports only delete and recreate.");
        }
        return new PubSubResponsePlan(failure, failureCount == null ? (failure == null ? 0 : -1) : failureCount,
                oauthError, expectedTokenRequests, lostResponseCount == null ? 0 : lostResponseCount,
                booleanProperty(properties, "shortLivedAccessToken"), booleanProperty(properties, "expireAccessToken"),
                topicAction, booleanProperty(properties, "disconnect"));
    }

    private static boolean booleanProperty(Map<String, Object> properties, String name) {
        Object value = properties.get(name);
        if (value != null && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("Pub/Sub " + name + " must be a boolean.");
        }
        return Boolean.TRUE.equals(value);
    }

    private static String stringProperty(Map<String, Object> properties, String name) {
        Object value = properties.get(name);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("Pub/Sub " + name + " must be a string.");
        }
        return (String) value;
    }

    private static Integer integerProperty(Map<String, Object> properties, String name) {
        Object value = properties.get(name);
        if (value != null && !(value instanceof Integer)) {
            throw new IllegalArgumentException("Pub/Sub " + name + " must be an integer.");
        }
        return (Integer) value;
    }

    private static void verifyBody(Object expectedBody, byte[] actualBody, String description) {
        if (expectedBody instanceof String stringBody) {
            assertArrayEquals(stringBody.getBytes(StandardCharsets.UTF_8), actualBody, description);
        } else if (expectedBody instanceof byte[] bytes) {
            assertArrayEquals(bytes, actualBody, description);
        } else {
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(actualBody))) {
                assertEquals(expectedBody, input.readObject(), description);
            } catch (IOException | ClassNotFoundException exception) {
                fail(description + " The payload is not a serialized Java object.", exception);
            }
        }
    }

    private static CamelExchangeContextPropagation registerRuntimeBeans(CamelContext camelContext) {
        Registry registry = camelContext.getRegistry();
        CDI<Object> container = CDI.current();
        bindCdiProcessor(container, registry, "contextPropagationProcessor");
        bindCdiProcessor(container, registry, "contextRestoreProcessor");
        registry.bind(
                "customGooglePubSubSerializer",
                GooglePubsubSerializer.class,
                new CustomGooglePubSubSerializer()
        );
        Instance<CamelExchangeContextPropagation> contextPropagation = container.select(
                CamelExchangeContextPropagation.class
        );
        if (!contextPropagation.isResolvable()) {
            throw new IllegalStateException("Pub/Sub gRPC fixture cannot resolve CDI context propagation.");
        }
        return contextPropagation.get();
    }

    private static void bindCdiProcessor(CDI<Object> cdi, Registry registry, String name) {
        Instance<Processor> processors = cdi.select(Processor.class, NamedLiteral.of(name));
        if (!processors.isResolvable()) {
            throw new IllegalStateException(
                    "Pub/Sub gRPC fixture cannot resolve CDI processor '" + name + "'."
            );
        }
        registry.bind(name, Processor.class, processors.get());
    }

    private static void initializeRequestContext(
            CamelExchangeContextPropagation contextPropagation,
            Exchange exchange
    ) {
        Map<String, Object> headers = exchange.getMessage().getHeaders();
        contextPropagation.initRequestContext(headers);
        Object authorization = exchange.getMessage().getHeader(HttpHeaders.AUTHORIZATION);
        contextPropagation.removeContextHeaders(headers);
        if (authorization != null) {
            exchange.getMessage().setHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        exchange.setProperty(
                REQUEST_CONTEXT_PROPAGATION_SNAPSHOT,
                contextPropagation.createContextSnapshot()
        );
    }

    private static MethodDescriptor<PublishRequest, PublishResponse> publishMethod() {
        return MethodDescriptor.<PublishRequest, PublishResponse>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Publish"))
                .setRequestMarshaller(ProtoUtils.marshaller(PublishRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(PublishResponse.getDefaultInstance()))
                .build();
    }

    private static PubSubProducerNode findProducerNode(
            List<RouteDefinition> routes,
            SnapshotFixtureDefinition fixtureDefinition
    ) {
        List<PubSubProducerNode> matchingNodes = new ArrayList<>();
        for (SnapshotRouteNodes.NodeMatch match : SnapshotRouteNodes.findById(routes, fixtureDefinition.getNodeId())) {
            ToDynamicDefinition endpoint = SnapshotRouteNodes.requireSingle(
                    SnapshotRouteNodes.findDynamicEndpoints(match.definition(), ENDPOINT_PREFIX),
                    "Pub/Sub gRPC fixture '" + fixtureDefinition.getId() + "' sender node '"
                            + fixtureDefinition.getNodeId()
                            + "' must contain exactly one Pub/Sub dynamic endpoint"
            );
            matchingNodes.add(new PubSubProducerNode(match.route(), endpoint));
        }
        return SnapshotRouteNodes.requireSingle(
                matchingNodes,
                "Pub/Sub gRPC fixture '" + fixtureDefinition.getId()
                        + "' expected one sender node '" + fixtureDefinition.getNodeId()
                        + "' in deployment '" + fixtureDefinition.getDeploymentId() + "'"
        );
    }

    private static void validateServiceAccountKey(PubSubEndpointUri endpointUri, String fixtureId) {
        String resource = endpointUri.parameters().get(SERVICE_ACCOUNT_KEY_PARAMETER);
        if (resource == null || !resource.startsWith(BASE64_RESOURCE_PREFIX)) {
            throw new IllegalArgumentException(
                    "Pub/Sub gRPC fixture '" + fixtureId
                            + "' endpoint must contain a Base64 service account key resource."
            );
        }

        byte[] credentialJson;
        try {
            credentialJson = Base64.getDecoder().decode(resource.substring(BASE64_RESOURCE_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Pub/Sub gRPC fixture '" + fixtureId
                            + "' endpoint contains an invalid Base64 service account key.",
                    exception
            );
        }

        ServiceAccountCredentials credentials;
        try (ByteArrayInputStream input = new ByteArrayInputStream(credentialJson)) {
            credentials = ServiceAccountCredentials.fromStream(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Pub/Sub gRPC fixture '" + fixtureId
                            + "' endpoint contains an invalid service account credentials file.",
                    exception
            );
        }

        requireNonBlankCredentialValue(credentials.getProjectId(), fixtureId, "project ID");
        requireNonBlankCredentialValue(credentials.getClientId(), fixtureId, "client ID");
        requireNonBlankCredentialValue(credentials.getClientEmail(), fixtureId, "client email");
        String privateKeyId = requireNonBlankCredentialValue(
                credentials.getPrivateKeyId(),
                fixtureId,
                "private key ID"
        );
        if (!privateKeyId.matches("[0-9a-fA-F]{40}")) {
            throw invalidCredential(fixtureId, "private key ID must contain 40 hexadecimal characters");
        }

        PrivateKey privateKey = credentials.getPrivateKey();
        if (!(privateKey instanceof RSAPrivateKey rsaPrivateKey)
                || !"RSA".equals(privateKey.getAlgorithm())
                || !"PKCS#8".equals(privateKey.getFormat())
                || rsaPrivateKey.getModulus().bitLength() != 2048) {
            throw invalidCredential(fixtureId, "private key must be a 2048-bit RSA PKCS#8 key");
        }
        if (credentials.sign(CREDENTIAL_VALIDATION_PAYLOAD).length == 0) {
            throw invalidCredential(fixtureId, "private key cannot create a signature");
        }
    }

    private static String requireNonBlankCredentialValue(
            String value,
            String fixtureId,
            String valueName
    ) {
        if (value == null || value.isBlank()) {
            throw invalidCredential(fixtureId, valueName + " is missing");
        }
        return value;
    }

    private static IllegalArgumentException invalidCredential(String fixtureId, String reason) {
        return new IllegalArgumentException(
                "Pub/Sub gRPC fixture '" + fixtureId
                        + "' endpoint contains an invalid service account credentials file: " + reason + '.'
        );
    }

    private static String messageFailure(String fixtureId, int messageNumber, String valueType) {
        return "Pub/Sub gRPC fixture '" + fixtureId + "' message " + messageNumber
                + " has an unexpected " + valueType + ".";
    }

    private record PubSubProducerNode(
            RouteDefinition route,
            ToDynamicDefinition endpoint
    ) {
    }

    private record PreparedRequest(
            Map<String, Object> properties,
            Exchange exchange
    ) {
    }

    private record PublishedResponse(String topic, PublishResponse response, boolean responseLost) {
    }

    private record ExpectedDelivery(SnapshotFixtureRequestExpectation expectation, String messageId) {
    }

    private record PubSubResponsePlan(
            Status failure,
            int failureCount,
            String oauthError,
            Integer expectedTokenRequests,
            int lostPublishResponseCount,
            boolean shortLivedAccessToken,
            boolean expireAccessToken,
            String topicAction,
            boolean disconnect
    ) {
    }

    private record PubSubEndpointUri(
            String projectId,
            String destination,
            String topic,
            Map<String, String> parameters
    ) {
        private String withLocalPublisher(String componentName, String credentialsResource, String publisherEndpoint) {
            StringBuilder uri = new StringBuilder(componentName).append(':')
                    .append(projectId)
                    .append(':')
                    .append(destination);
            Map<String, String> localParameters = new LinkedHashMap<>(parameters);
            localParameters.put(SERVICE_ACCOUNT_KEY_PARAMETER, credentialsResource);
            localParameters.put("pubsubEndpoint", publisherEndpoint);
            localParameters.forEach((name, value) -> uri
                    .append(uri.indexOf("?") < 0 ? '?' : '&')
                    .append(name)
                    .append('=')
                    .append(value));
            return uri.toString();
        }

        private static PubSubEndpointUri parse(String uri, String fixtureId) {
            int queryStart = uri.indexOf('?');
            String endpoint = queryStart < 0 ? uri : uri.substring(0, queryStart);
            String query = queryStart < 0 ? "" : uri.substring(queryStart + 1);
            if (!endpoint.startsWith(ENDPOINT_PREFIX)) {
                throw new IllegalArgumentException(
                        "Pub/Sub gRPC fixture '" + fixtureId
                                + "' must target a google-pubsub endpoint, but found '" + uri + "'."
                );
            }

            String remaining = endpoint.substring(ENDPOINT_PREFIX.length());
            int destinationSeparator = remaining.indexOf(':');
            if (destinationSeparator <= 0 || destinationSeparator == remaining.length() - 1) {
                throw new IllegalArgumentException(
                        "Pub/Sub gRPC fixture '" + fixtureId
                                + "' endpoint must define a project and topic."
                );
            }
            String projectId = remaining.substring(0, destinationSeparator);
            String destination = remaining.substring(destinationSeparator + 1);
            requireStaticValue(projectId, fixtureId, "project ID");
            requireStaticValue(destination, fixtureId, "topic");
            if (destination.contains(":")) {
                throw new IllegalArgumentException(
                        "Pub/Sub gRPC fixture '" + fixtureId
                                + "' publisher endpoint cannot define a subscription."
                );
            }
            return new PubSubEndpointUri(
                    projectId,
                    destination,
                    "projects/" + projectId + "/topics/" + destination,
                    SnapshotEndpointParameters.parse(query, name -> new IllegalArgumentException(
                            "Pub/Sub gRPC fixture '" + fixtureId
                                    + "' does not support duplicate endpoint parameter '" + name + "'."
                    ))
            );
        }

        private static void requireStaticValue(String value, String fixtureId, String valueName) {
            if (value.isBlank() || value.contains("${") || value.contains("{{")) {
                throw new IllegalArgumentException(
                        "Pub/Sub gRPC fixture '" + fixtureId
                                + "' requires a static " + valueName + "."
                );
            }
        }
    }
}
