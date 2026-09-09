package org.qubership.integration.platform.engine.routes.fixture;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublishResponse;
import com.google.pubsub.v1.PubsubMessage;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.google.pubsub.serializer.GooglePubsubSerializer;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.spi.Registry;
import org.qubership.integration.platform.engine.camel.components.pubsub.CustomGooglePubSubComponent;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.support.SnapshotRouteNodes;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.service.debugger.util.CustomGooglePubSubSerializer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMap;

class PubSubGrpcSnapshotFixtureProvider implements SnapshotFixtureProvider {
    private static final String PROVIDER_ID = "pubsub-grpc";
    private static final String COMPONENT_NAME = "google-pubsub";
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String ENDPOINT_PREFIX = COMPONENT_NAME + ':';
    private static final String SERVICE_NAME = "google.pubsub.v1.Publisher";
    private static final String SERVICE_ACCOUNT_KEY_PARAMETER = "serviceAccountKey";
    private static final String BASE64_RESOURCE_PREFIX = "base64:";
    private static final String MESSAGE_ID_PREFIX = "snapshot-message-";
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
        private final List<PublishedMessage> publishedMessages = new CopyOnWriteArrayList<>();
        private final AtomicLong messageIdSequence = new AtomicLong();
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
        public void start() throws IOException {
            ServerServiceDefinition publisherService = ServerServiceDefinition.builder(SERVICE_NAME)
                    .addMethod(publishMethod(), ServerCalls.asyncUnaryCall(this::publish))
                    .build();
            server = NettyServerBuilder.forAddress(new InetSocketAddress(LOOPBACK_HOST, 0))
                    .directExecutor()
                    .addService(publisherService)
                    .build()
                    .start();
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> deploymentRoutes) throws Exception {
            registerRuntimeBeans(camelContext);

            String componentName = "snapshot-pubsub-" + server.getPort();
            CustomGooglePubSubComponent component = new CustomGooglePubSubComponent();
            component.setEndpoint(LOOPBACK_HOST + ':' + server.getPort());
            component.setAuthenticate(false);
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
                producerNode.endpoint().setUri(endpointUri.withMaskedServiceAccountKey(componentName));

                String topic = endpointUri.topic();
                if (!topics.add(topic)) {
                    throw new IllegalArgumentException(
                            "Pub/Sub gRPC fixtures in deployment '" + deploymentId
                                    + "' cannot share topic '" + topic + "'."
                    );
                }
                runtimesByFixtureId.get(binding.definition().getId()).configure(endpointUri);
                bindingsByRoute.computeIfAbsent(producerNode.route(), ignored -> new ArrayList<>())
                        .add(binding);
            }

            for (Map.Entry<RouteDefinition, List<SnapshotFixtureBinding>> entry : bindingsByRoute.entrySet()) {
                AdviceWith.adviceWith(camelContext, entry.getKey(), false, advice -> {
                    for (SnapshotFixtureBinding binding : entry.getValue()) {
                        advice.weaveById(binding.definition().getNodeId())
                                .before()
                                .process(exchange -> capturePreparedRequest(binding, exchange));
                    }
                });
            }
        }

        @Override
        public void verify() {
            int expectedMessageCount = runtimesByFixtureId.values().stream()
                    .mapToInt(PubSubFixtureRuntime::expectedMessageCount)
                    .sum();
            assertEquals(
                    expectedMessageCount,
                    publishedMessages.size(),
                    "Pub/Sub gRPC fixture received an unexpected total number of messages."
            );
            runtimesByFixtureId.values().forEach(runtime -> runtime.verify(publishedMessages));
        }

        @Override
        public void close() {
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
            PublishResponse.Builder response = PublishResponse.newBuilder();
            for (PubsubMessage message : request.getMessagesList()) {
                publishedMessages.add(new PublishedMessage(request.getTopic(), message));
                response.addMessageIds(MESSAGE_ID_PREFIX + messageIdSequence.incrementAndGet());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
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
            runtimesByFixtureId.get(binding.definition().getId()).addPreparedRequest(
                    new PreparedRequest(immutableMap(exchange.getProperties()))
            );
        }
    }

    private static final class PubSubFixtureRuntime {
        private final SnapshotFixtureBinding binding;
        private final GooglePubsubSerializer serializer = new CustomGooglePubSubSerializer();
        private final List<PreparedRequest> preparedRequests = new CopyOnWriteArrayList<>();
        private PubSubEndpointUri endpointUri;

        private PubSubFixtureRuntime(SnapshotFixtureBinding binding) {
            this.binding = binding;
        }

        private void configure(PubSubEndpointUri endpointUri) {
            this.endpointUri = endpointUri;
        }

        private void addPreparedRequest(PreparedRequest preparedRequest) {
            preparedRequests.add(preparedRequest);
        }

        private int expectedMessageCount() {
            return binding.interaction().getExpectedRequest().getCount();
        }

        private void verify(List<PublishedMessage> allMessages) {
            SnapshotFixtureRequestExpectation expectation = binding.interaction().getExpectedRequest();
            String fixtureId = binding.definition().getId();
            List<PublishedMessage> messages = allMessages.stream()
                    .filter(message -> endpointUri.topic().equals(message.topic()))
                    .toList();
            assertEquals(
                    expectation.getCount(),
                    messages.size(),
                    () -> "Pub/Sub gRPC fixture '" + fixtureId
                            + "' received an unexpected number of messages."
            );
            assertEquals(
                    messages.size(),
                    preparedRequests.size(),
                    () -> "Pub/Sub gRPC fixture '" + fixtureId
                            + "' prepared a message that did not reach the publisher service."
            );

            for (int index = 0; index < messages.size(); index++) {
                verifyMessage(
                        fixtureId,
                        index + 1,
                        expectation,
                        messages.get(index),
                        preparedRequests.get(index)
                );
            }
        }

        private void verifyMessage(
                String fixtureId,
                int messageNumber,
                SnapshotFixtureRequestExpectation expectation,
                PublishedMessage publishedMessage,
                PreparedRequest preparedRequest
        ) {
            assertEquals(
                    expectation.getDestination(),
                    endpointUri.destination(),
                    () -> messageFailure(fixtureId, messageNumber, "destination")
            );
            assertEquals(
                    endpointUri.topic(),
                    publishedMessage.topic(),
                    () -> messageFailure(fixtureId, messageNumber, "topic")
            );
            if (expectation.hasBody()) {
                assertArrayEquals(
                        expectedBody(expectation.getBody()),
                        publishedMessage.message().getData().toByteArray(),
                        () -> messageFailure(fixtureId, messageNumber, "body")
                );
            }
            SnapshotValueAssertions.assertMapValues(
                    expectation.getHeaders(),
                    publishedMessage.message().getAttributesMap(),
                    "Pub/Sub gRPC fixture '" + fixtureId + "' message " + messageNumber
                            + " has an unexpected attribute"
            );
            SnapshotValueAssertions.assertMapValues(
                    expectation.getProperties(),
                    preparedRequest.properties(),
                    "Pub/Sub gRPC fixture '" + fixtureId + "' message " + messageNumber
                            + " has an unexpected property"
            );
        }

        private byte[] expectedBody(Object body) {
            if (body instanceof String stringBody) {
                return stringBody.getBytes(StandardCharsets.UTF_8);
            }
            if (body instanceof byte[] bytes) {
                return bytes.clone();
            }
            try {
                return serializer.serialize(body);
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "Cannot serialize the expected Pub/Sub message body.",
                        exception
                );
            }
        }
    }

    private static void validateBinding(SnapshotFixtureBinding binding) {
        String fixtureId = binding.definition().getId();
        SnapshotFixtureRequestExpectation expectation =
                SnapshotFixtureValidation.requireMessageExpectation(binding, "Pub/Sub gRPC");
        if (expectation.getMethod() != null
                || expectation.getPath() != null
                || expectation.getQuery() != null
                || expectation.getKey() != null) {
            throw new IllegalArgumentException(
                    "Pub/Sub gRPC fixture '" + fixtureId
                            + "' does not support HTTP method, path, query, or key expectations."
            );
        }
    }

    private static void registerRuntimeBeans(CamelContext camelContext) {
        Registry registry = camelContext.getRegistry();
        Processor noOpProcessor = exchange -> {
        };
        bindProcessor(registry, "contextPropagationProcessor", noOpProcessor);
        bindProcessor(registry, "contextRestoreProcessor", noOpProcessor);
        registry.bind(
                "customGooglePubSubSerializer",
                GooglePubsubSerializer.class,
                new CustomGooglePubSubSerializer()
        );
    }

    private static void bindProcessor(Registry registry, String name, Processor processor) {
        registry.bind(name, Processor.class, processor);
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

        requireCredentialValue(endpointUri.projectId(), credentials.getProjectId(), fixtureId, "project ID");
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

    private static void requireCredentialValue(
            String expected,
            String actual,
            String fixtureId,
            String valueName
    ) {
        if (!expected.equals(actual)) {
            throw invalidCredential(fixtureId, valueName + " does not match the endpoint");
        }
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
            Map<String, Object> properties
    ) {
    }

    private record PublishedMessage(
            String topic,
            PubsubMessage message
    ) {
    }

    private record PubSubEndpointUri(
            String projectId,
            String destination,
            String topic,
            Map<String, String> parameters
    ) {
        private String withMaskedServiceAccountKey(String componentName) {
            StringBuilder uri = new StringBuilder(componentName).append(':')
                    .append(projectId)
                    .append(':')
                    .append(destination);
            parameters.forEach((name, value) -> uri
                    .append(uri.indexOf("?") < 0 ? '?' : '&')
                    .append(name)
                    .append('=')
                    .append(SERVICE_ACCOUNT_KEY_PARAMETER.equals(name)
                            ? BASE64_RESOURCE_PREFIX + "REDACTED"
                            : value));
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
