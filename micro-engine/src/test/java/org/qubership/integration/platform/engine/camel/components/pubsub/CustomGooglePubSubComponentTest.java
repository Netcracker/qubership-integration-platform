package org.qubership.integration.platform.engine.camel.components.pubsub;

import com.google.api.core.ApiService;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import io.grpc.Attributes;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerTransportFilter;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.apache.camel.Endpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.PubSubTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomGooglePubSubComponentTest {

    private static final String SUBSCRIPTION_NAME = "projects/project-id/subscriptions/subscription-name";

    private CustomGooglePubSubComponent component;
    private final List<Subscriber> subscribers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        component = PubSubTestUtils.newComponent();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Subscriber subscriber : subscribers) {
            subscriber.stopAsync();
            try {
                subscriber.awaitTerminated(10, TimeUnit.SECONDS);
            } catch (IllegalStateException exception) {
                assertEquals(ApiService.State.FAILED, subscriber.state());
            }
        }
        component.getCamelContext().close();
    }

    @Test
    void shouldCloseEachTransportWhenSubscriptionDoesNotExist() throws Exception {
        try (PubSubService service = new PubSubService(Status.NOT_FOUND)) {
            component.setEndpoint(service.getEndpoint());

            for (int attempt = 1; attempt <= 3; attempt++) {
                Subscriber subscriber = newSubscriber();
                CompletableFuture<Throwable> failure = new CompletableFuture<>();
                subscriber.addListener(new ApiService.Listener() {
                    @Override
                    public void failed(ApiService.State from, Throwable cause) {
                        failure.complete(cause);
                    }
                }, Runnable::run);

                subscriber.startAsync();

                assertEquals(Status.Code.NOT_FOUND,
                        Status.fromThrowable(failure.get(10, TimeUnit.SECONDS)).getCode());
                assertEquals(ApiService.State.FAILED, subscriber.state());
                assertEquals(SUBSCRIPTION_NAME, service.awaitRequest().getSubscription());
                service.awaitTransportTermination();
                assertEquals(attempt, service.openedTransports.get());
                assertEquals(attempt, service.closedTransports.get());
            }
        }
    }

    @Test
    void shouldCloseTransportWhenSubscriberStops() throws Exception {
        try (PubSubService service = new PubSubService(null)) {
            component.setEndpoint(service.getEndpoint());
            Subscriber subscriber = newSubscriber();

            subscriber.startAsync().awaitRunning(10, TimeUnit.SECONDS);

            assertEquals(SUBSCRIPTION_NAME, service.awaitRequest().getSubscription());
            subscriber.stopAsync().awaitTerminated(10, TimeUnit.SECONDS);
            service.awaitTransportTermination();
            assertEquals(1, service.openedTransports.get());
            assertEquals(1, service.closedTransports.get());
        }
    }

    @Test
    void shouldCreateCustomEndpointAndCopyComponentSettingsWhenRemainingIsValid() throws Exception {
        component.setServiceAccountKey("classpath:service-account.json");
        component.setAuthenticate(false);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("maxMessagesPerPoll", 5);
        parameters.put("synchronousPull", true);

        Endpoint endpoint = component.createEndpoint(
                "google-pubsub:project-id:destination-name",
                "project-id:destination-name",
                parameters
        );

        assertInstanceOf(CustomGooglePubSubEndpoint.class, endpoint);
        CustomGooglePubSubEndpoint pubsubEndpoint = (CustomGooglePubSubEndpoint) endpoint;
        assertSame(component, pubsubEndpoint.getComponent());
        assertEquals("project-id", pubsubEndpoint.getProjectId());
        assertEquals("destination-name", pubsubEndpoint.getDestinationName());
        assertEquals("classpath:service-account.json", pubsubEndpoint.getServiceAccountKey());
        assertFalse(pubsubEndpoint.isAuthenticate());
        assertEquals(5, pubsubEndpoint.getMaxMessagesPerPoll());
        assertTrue(pubsubEndpoint.isSynchronousPull());
    }

    @Test
    void shouldThrowWhenRemainingDoesNotContainDestinationName() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                component.createEndpoint("google-pubsub:project-id", "project-id", new HashMap<>())
        );

        assertEquals(
                "Google PubSub Endpoint format \"projectId:destinationName[:subscriptionName]\"",
                exception.getMessage()
        );
    }

    @Test
    void shouldCreateSubscriberWhenComponentEndpointIsBlank() throws Exception {
        component.setEndpoint("  ");

        Subscriber subscriber = newSubscriber();

        assertEquals(SUBSCRIPTION_NAME, subscriber.getSubscriptionNameString());
        assertEquals(ApiService.State.NEW, subscriber.state());
    }

    @Test
    void shouldUseApplicationDefaultCredentialsWhenServiceAccountKeyIsEmpty() throws Exception {
        component.setEndpoint("localhost:8085");
        CustomGooglePubSubEndpoint endpoint = new CustomGooglePubSubEndpoint(
                "google-pubsub:project-id:subscription-name", component);
        endpoint.setAuthenticate(true);
        GoogleCredentials credentials = GoogleCredentials.create(new AccessToken("test-token", new Date(Long.MAX_VALUE)));

        try (MockedStatic<GoogleCredentials> googleCredentials = mockStatic(GoogleCredentials.class)) {
            googleCredentials.when(GoogleCredentials::getApplicationDefault).thenReturn(credentials);

            Subscriber subscriber = newSubscriber(endpoint);

            assertEquals(SUBSCRIPTION_NAME, subscriber.getSubscriptionNameString());
            assertEquals(ApiService.State.NEW, subscriber.state());
            googleCredentials.verify(GoogleCredentials::getApplicationDefault);
        }
    }

    @Test
    void shouldPropagateFailureWhenApplicationDefaultCredentialsAreUnavailable() {
        component.setEndpoint("localhost:8085");
        CustomGooglePubSubEndpoint endpoint = new CustomGooglePubSubEndpoint(
                "google-pubsub:project-id:subscription-name", component);
        endpoint.setAuthenticate(true);
        IOException failure = new IOException("Application default credentials are unavailable");

        try (MockedStatic<GoogleCredentials> googleCredentials = mockStatic(GoogleCredentials.class)) {
            googleCredentials.when(GoogleCredentials::getApplicationDefault).thenThrow(failure);

            assertSame(failure, assertThrows(IOException.class, () -> newSubscriber(endpoint)));
        }
    }

    @Test
    void shouldCreateSubscriberWhenServiceAccountResourceIsValid(@TempDir Path directory) throws Exception {
        component.setEndpoint("localhost:8085");
        CustomGooglePubSubEndpoint endpoint = new CustomGooglePubSubEndpoint(
                "google-pubsub:project-id:subscription-name", component);
        endpoint.setAuthenticate(true);
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        String privateKey = Base64.getEncoder().encodeToString(keyPairGenerator.generateKeyPair().getPrivate().getEncoded());
        Path serviceAccountKey = directory.resolve("service-account.json");
        Files.writeString(serviceAccountKey, """
                {
                  "type": "service_account",
                  "project_id": "project-id",
                  "client_id": "client-id",
                  "client_email": "subscriber@project-id.iam.gserviceaccount.com",
                  "private_key_id": "key-id",
                  "private_key": "-----BEGIN PRIVATE KEY-----\\n%s\\n-----END PRIVATE KEY-----\\n"
                }
                """.formatted(privateKey));
        endpoint.setServiceAccountKey(serviceAccountKey.toUri().toString());

        Subscriber subscriber = newSubscriber(endpoint);

        assertEquals(SUBSCRIPTION_NAME, subscriber.getSubscriptionNameString());
        assertEquals(ApiService.State.NEW, subscriber.state());
    }

    @Test
    void shouldRejectMissingServiceAccountResourceWhenAuthenticationIsEnabled() {
        component.setEndpoint("localhost:8085");
        CustomGooglePubSubEndpoint endpoint = new CustomGooglePubSubEndpoint(
                "google-pubsub:project-id:subscription-name", component);
        endpoint.setAuthenticate(true);
        endpoint.setServiceAccountKey("classpath:missing-service-account.json");

        IOException failure = assertThrows(IOException.class, () -> newSubscriber(endpoint));

        assertTrue(failure.getMessage().contains("missing-service-account.json"));
    }

    private Subscriber newSubscriber() throws IOException {
        CustomGooglePubSubEndpoint endpoint = new CustomGooglePubSubEndpoint(
                "google-pubsub:project-id:subscription-name", component);
        endpoint.setAuthenticate(false);
        endpoint.setServiceAccountKey("classpath:missing-service-account.json");
        return newSubscriber(endpoint);
    }

    private Subscriber newSubscriber(CustomGooglePubSubEndpoint endpoint) throws IOException {
        Subscriber subscriber = component.getSubscriber(SUBSCRIPTION_NAME, (message, consumer) -> consumer.ack(), endpoint);
        subscribers.add(subscriber);
        return subscriber;
    }

    private static final class PubSubService implements AutoCloseable {

        private final AtomicInteger openedTransports = new AtomicInteger();
        private final AtomicInteger closedTransports = new AtomicInteger();
        private final BlockingQueue<Attributes> terminatedTransports = new LinkedBlockingQueue<>();
        private final BlockingQueue<StreamingPullRequest> requests = new LinkedBlockingQueue<>();
        private final Server server;

        private PubSubService(Status failure) throws IOException {
            MethodDescriptor<StreamingPullRequest, StreamingPullResponse> streamingPull = MethodDescriptor
                    .<StreamingPullRequest, StreamingPullResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                    .setFullMethodName("google.pubsub.v1.Subscriber/StreamingPull")
                    .setRequestMarshaller(ProtoUtils.marshaller(StreamingPullRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(StreamingPullResponse.getDefaultInstance()))
                    .build();
            ServerServiceDefinition service = ServerServiceDefinition.builder("google.pubsub.v1.Subscriber")
                    .addMethod(streamingPull, ServerCalls.asyncBidiStreamingCall(responseObserver ->
                            new StreamObserver<StreamingPullRequest>() {
                                private boolean initialRequestReceived;

                                @Override
                                public void onNext(StreamingPullRequest request) {
                                    if (initialRequestReceived) {
                                        return;
                                    }
                                    initialRequestReceived = true;
                                    requests.add(request);
                                    if (failure != null) {
                                        responseObserver.onError(failure.asRuntimeException());
                                    }
                                }

                                @Override
                                public void onError(Throwable throwable) {
                                }

                                @Override
                                public void onCompleted() {
                                    responseObserver.onCompleted();
                                }
                            }))
                    .build();
            server = ServerBuilder.forPort(0)
                    .directExecutor()
                    .addService(service)
                    .addTransportFilter(new ServerTransportFilter() {
                        @Override
                        public Attributes transportReady(Attributes attributes) {
                            openedTransports.incrementAndGet();
                            return attributes;
                        }

                        @Override
                        public void transportTerminated(Attributes attributes) {
                            closedTransports.incrementAndGet();
                            terminatedTransports.add(attributes);
                        }
                    })
                    .build()
                    .start();
        }

        private String getEndpoint() {
            return "localhost:" + server.getPort();
        }

        private StreamingPullRequest awaitRequest() throws InterruptedException {
            StreamingPullRequest request = requests.poll(10, TimeUnit.SECONDS);
            assertNotNull(request, "Subscriber must send a streaming pull request");
            return request;
        }

        private void awaitTransportTermination() throws InterruptedException {
            assertNotNull(terminatedTransports.poll(10, TimeUnit.SECONDS), "Subscriber must close its transport");
        }

        @Override
        public void close() throws InterruptedException {
            server.shutdownNow();
            assertTrue(server.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
