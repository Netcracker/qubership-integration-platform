package org.qubership.integration.platform.engine.camel.components.pubsub;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiExceptionFactory;
import com.google.api.gax.rpc.UnaryCallable;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;
import io.grpc.Status;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.google.pubsub.GooglePubsubConstants;
import org.apache.camel.component.google.pubsub.consumer.GooglePubsubAcknowledge;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.spi.Synchronization;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.PubSubTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomGooglePubSubConsumerTest {

    @Test
    void shouldCloseSubscriberWithoutStartingWhenCreationFinishesAfterConsumerStops() throws Exception {
        CustomGooglePubSubEndpoint endpoint = spy(PubSubTestUtils.newEndpointWithConcurrentConsumers(1));
        CustomGooglePubSubComponent component = spy((CustomGooglePubSubComponent) endpoint.getComponent());
        doReturn(component).when(endpoint).getComponent();
        CustomGooglePubSubConsumer consumer = new CustomGooglePubSubConsumer(endpoint, mock(Processor.class));
        ExecutorService executor = mock(ExecutorService.class);
        doReturn(executor).when(endpoint).createExecutor(consumer);
        Subscriber subscriber = mock(Subscriber.class);
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch finishCreation = new CountDownLatch(1);
        doAnswer(invocation -> {
            creating.countDown();
            assertTrue(finishCreation.await(5, TimeUnit.SECONDS));
            return subscriber;
        }).when(component).getSubscriber(anyString(), any(), eq(endpoint));

        consumer.start();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(task.capture());
        doReturn(null).when(endpoint).getCamelContext();
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<?> running = worker.submit(task.getValue());
            try {
                assertTrue(creating.await(5, TimeUnit.SECONDS));
                consumer.stop();
            } finally {
                finishCreation.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
        } finally {
            consumer.stop();
            component.getCamelContext().close();
        }

        verify(subscriber, never()).startAsync();
        verify(subscriber).stopAsync();
        verify(executor).shutdownNow();
    }

    @Test
    void shouldSubmitSubscriberWrapperForEachConcurrentConsumerWhenStarted() {
        CustomGooglePubSubEndpoint customGooglePubSubEndpoint = spy(PubSubTestUtils.newEndpointWithConcurrentConsumers(3));
        Processor processor = mock(Processor.class);
        CustomGooglePubSubConsumer consumer = new CustomGooglePubSubConsumer(customGooglePubSubEndpoint, processor);
        ExecutorService executor = mock(ExecutorService.class);
        Future<?> submittedTask = mock(Future.class);

        doReturn(executor).when(customGooglePubSubEndpoint).createExecutor(consumer);
        doReturn(submittedTask).when(executor).submit(any(Runnable.class));

        consumer.start();
        doReturn(null).when(customGooglePubSubEndpoint).getCamelContext();

        try {
            verify(customGooglePubSubEndpoint).createExecutor(consumer);
            verify(executor, times(3)).submit(any(Runnable.class));
        } finally {
            consumer.stop();
        }

        verify(executor).shutdownNow();
    }

    @Test
    void shouldPreserveSynchronousBatchMetadataAndUseManualAcknowledgmentsWhenConfigured() throws Exception {
        List<Exchange> received = new CopyOnWriteArrayList<>();
        try (SynchronousConsumerRun run = new SynchronousConsumerRun(GooglePubsubConstants.AckMode.NONE, received::add)) {
            UnaryCallable<AcknowledgeRequest, Empty> acknowledge = mock(UnaryCallable.class);
            UnaryCallable<ModifyAckDeadlineRequest, Empty> negativeAcknowledge = mock(UnaryCallable.class);
            when(run.stub.acknowledgeCallable()).thenReturn(acknowledge);
            when(run.stub.modifyAckDeadlineCallable()).thenReturn(negativeAcknowledge);
            run.responses.add(ApiFutures.immediateFuture(PullResponse.newBuilder()
                    .addReceivedMessages(synchronousMessage("first", "ack-first", new byte[] {65, 66}))
                    .addReceivedMessages(synchronousMessage("second", "ack-second", new byte[] {0, (byte) 255}))
                    .build()));
            ApiFuture<PullResponse> pending = run.pendingResponse();
            run.responses.add(pending);
            run.start();
            run.awaitPending();

            assertEquals(2, received.size());
            PullRequest request = run.requests.getFirst();
            assertEquals("projects/project-id/subscriptions/subscription-name", request.getSubscription());
            assertEquals(7, request.getMaxMessages());
            assertFalse(request.getReturnImmediately());
            for (int index = 0; index < received.size(); index++) {
                Exchange exchange = received.get(index);
                String id = index == 0 ? "first" : "second";
                assertEquals(id, exchange.getMessage().getHeader(GooglePubsubConstants.MESSAGE_ID));
                assertEquals("ack-" + id, exchange.getMessage().getHeader(GooglePubsubConstants.ACK_ID));
                assertEquals(Timestamp.newBuilder().setSeconds(123).build(),
                        exchange.getMessage().getHeader(GooglePubsubConstants.PUBLISH_TIME));
                assertEquals(Map.of("source", id), exchange.getMessage().getHeader(GooglePubsubConstants.ATTRIBUTES));
            }
            assertArrayEquals(new byte[] {65, 66}, received.getFirst().getMessage().getBody(byte[].class));
            assertArrayEquals(new byte[] {0, (byte) 255}, received.getLast().getMessage().getBody(byte[].class));
            GooglePubsubAcknowledge first = received.getFirst().getMessage().getHeader(
                    GooglePubsubConstants.GOOGLE_PUBSUB_ACKNOWLEDGE, GooglePubsubAcknowledge.class);
            first.ack(received.getFirst());
            GooglePubsubAcknowledge second = received.getLast().getMessage().getHeader(
                    GooglePubsubConstants.GOOGLE_PUBSUB_ACKNOWLEDGE, GooglePubsubAcknowledge.class);
            second.nack(received.getLast());
            verify(acknowledge).call(AcknowledgeRequest.newBuilder().setSubscription(request.getSubscription())
                    .addAckIds("ack-first").build());
            verify(negativeAcknowledge).call(ModifyAckDeadlineRequest.newBuilder().setSubscription(request.getSubscription())
                    .addAckIds("ack-second").setAckDeadlineSeconds(0).build());
            run.stop();
            verify(pending).cancel(true);
        }
    }

    @Test
    void shouldAcknowledgeSynchronousMessageWhenAutomaticCompletionSucceeds() throws Exception {
        List<Exchange> received = new CopyOnWriteArrayList<>();
        try (SynchronousConsumerRun run = new SynchronousConsumerRun(GooglePubsubConstants.AckMode.AUTO, received::add)) {
            UnaryCallable<AcknowledgeRequest, Empty> acknowledge = mock(UnaryCallable.class);
            when(run.stub.acknowledgeCallable()).thenReturn(acknowledge);
            run.responses.add(ApiFutures.immediateFuture(PullResponse.newBuilder()
                    .addReceivedMessages(synchronousMessage("automatic", "ack-automatic", new byte[] {1})).build()));
            run.responses.add(run.pendingResponse());
            run.start();
            run.awaitPending();

            Exchange exchange = received.getFirst();
            assertFalse(exchange.getMessage().getHeaders().containsKey(GooglePubsubConstants.GOOGLE_PUBSUB_ACKNOWLEDGE));
            List<Synchronization> completions = exchange.getExchangeExtension().handoverCompletions();
            assertEquals(1, completions.size());
            completions.getFirst().onComplete(exchange);
            verify(acknowledge).call(AcknowledgeRequest.newBuilder()
                    .setSubscription("projects/project-id/subscriptions/subscription-name")
                    .addAckIds("ack-automatic").build());
        }
    }

    @Test
    void shouldNegativelyAcknowledgeSynchronousMessageWhenAutomaticCompletionFails() throws Exception {
        List<Exchange> received = new CopyOnWriteArrayList<>();
        try (SynchronousConsumerRun run = new SynchronousConsumerRun(GooglePubsubConstants.AckMode.AUTO, received::add)) {
            UnaryCallable<ModifyAckDeadlineRequest, Empty> negativeAcknowledge = mock(UnaryCallable.class);
            when(run.stub.modifyAckDeadlineCallable()).thenReturn(negativeAcknowledge);
            run.responses.add(ApiFutures.immediateFuture(PullResponse.newBuilder()
                    .addReceivedMessages(synchronousMessage("automatic", "ack-automatic", new byte[] {1})).build()));
            run.responses.add(run.pendingResponse());
            run.start();
            run.awaitPending();

            Exchange exchange = received.getFirst();
            exchange.setException(new IllegalStateException("Processing failed"));
            List<Synchronization> completions = exchange.getExchangeExtension().handoverCompletions();
            assertEquals(1, completions.size());
            completions.getFirst().onFailure(exchange);
            verify(negativeAcknowledge).call(ModifyAckDeadlineRequest.newBuilder()
                    .setSubscription("projects/project-id/subscriptions/subscription-name")
                    .addAckIds("ack-automatic").setAckDeadlineSeconds(0).build());
        }
    }

    @Test
    void shouldCancelOutstandingSynchronousPullWhenConsumerStops() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        try (SynchronousConsumerRun run = new SynchronousConsumerRun(
                GooglePubsubConstants.AckMode.NONE, exchange -> processed.incrementAndGet())) {
            ApiFuture<PullResponse> pending = run.pendingResponse();
            run.responses.add(pending);
            run.start();
            run.awaitPending();
            run.stop();

            verify(pending).cancel(true);
            verify(run.stub).close();
            assertEquals(0, processed.get());
        }
    }

    @Test
    void shouldRetrySynchronousPullWhenApiFailureIsRetryable() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        try (SynchronousConsumerRun run = new SynchronousConsumerRun(
                GooglePubsubConstants.AckMode.NONE, exchange -> processed.incrementAndGet())) {
            run.responses.add(ApiFutures.immediateFailedFuture(ApiExceptionFactory.createException(
                    new IOException("Temporary pull failure"), GrpcStatusCode.of(Status.Code.UNAVAILABLE), true)));
            run.responses.add(ApiFutures.immediateFuture(PullResponse.newBuilder()
                    .addReceivedMessages(synchronousMessage("recovered", "ack-recovered", new byte[] {2})).build()));
            run.responses.add(run.pendingResponse());
            run.start();
            run.awaitPending();

            assertEquals(1, processed.get());
            assertEquals(3, run.requests.size());
            verify(run.stub, times(2)).close();
        }
    }

    @Test
    void shouldStopSynchronousWorkerWhenApiFailureIsNotRetryable() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        try (SynchronousConsumerRun run = new SynchronousConsumerRun(
                GooglePubsubConstants.AckMode.NONE, exchange -> processed.incrementAndGet())) {
            run.responses.add(ApiFutures.immediateFailedFuture(ApiExceptionFactory.createException(
                    new IOException("Pull permission rejected"), GrpcStatusCode.of(Status.Code.PERMISSION_DENIED), false)));
            run.start();
            run.awaitWorker();

            assertEquals(0, processed.get());
            assertEquals(1, run.requests.size());
            verify(run.stub).close();
        }
    }

    @Test
    void shouldRetrySynchronousPullWhenSubscriberCreationThrowsIOException() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        try (SynchronousConsumerRun run = new SynchronousConsumerRun(
                GooglePubsubConstants.AckMode.NONE, exchange -> processed.incrementAndGet())) {
            run.creationFailures.set(1);
            run.responses.add(ApiFutures.immediateFuture(PullResponse.newBuilder()
                    .addReceivedMessages(synchronousMessage("recovered", "ack-recovered", new byte[] {3})).build()));
            run.responses.add(run.pendingResponse());
            run.start();
            run.awaitPending();

            assertEquals(1, processed.get());
            assertEquals(2, run.requests.size());
            assertEquals(3, run.creationAttempts.get());
        }
    }

    @Test
    void shouldContinueSynchronousBatchWhenOneProcessorInvocationFails() throws Exception {
        AtomicInteger attempted = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("First synchronous message failed");
        try (SynchronousConsumerRun run = new SynchronousConsumerRun(GooglePubsubConstants.AckMode.NONE, exchange -> {
            if (attempted.incrementAndGet() == 1) {
                throw failure;
            }
        })) {
            run.responses.add(ApiFutures.immediateFuture(PullResponse.newBuilder()
                    .addReceivedMessages(synchronousMessage("first", "ack-first", new byte[] {1}))
                    .addReceivedMessages(synchronousMessage("second", "ack-second", new byte[] {2})).build()));
            run.responses.add(run.pendingResponse());
            run.start();
            run.awaitPending();

            assertEquals(2, attempted.get());
            verify(run.exceptionHandler).handleException(failure);
        }
    }

    private static ReceivedMessage synchronousMessage(String id, String ackId, byte[] body) {
        return ReceivedMessage.newBuilder().setAckId(ackId).setMessage(PubsubMessage.newBuilder()
                .setMessageId(id).setData(ByteString.copyFrom(body)).putAttributes("source", id)
                .setPublishTime(Timestamp.newBuilder().setSeconds(123))).build();
    }

    private static final class SynchronousConsumerRun implements AutoCloseable {
        private final DefaultCamelContext context = new DefaultCamelContext();
        private final CustomGooglePubSubComponent component = spy(new CustomGooglePubSubComponent());
        private final CustomGooglePubSubEndpoint endpoint;
        private final CustomGooglePubSubConsumer consumer;
        private final SubscriberStub stub = mock(SubscriberStub.class);
        private final UnaryCallable<PullRequest, PullResponse> pull = mock(UnaryCallable.class);
        private final ExecutorService executor = mock(ExecutorService.class);
        private final ExceptionHandler exceptionHandler = mock(ExceptionHandler.class);
        private final List<PullRequest> requests = new CopyOnWriteArrayList<>();
        private final Queue<ApiFuture<PullResponse>> responses = new ConcurrentLinkedQueue<>();
        private final AtomicInteger creationFailures = new AtomicInteger();
        private final AtomicInteger creationAttempts = new AtomicInteger();
        private final CountDownLatch pendingEntered = new CountDownLatch(1);
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private Future<?> running;
        private boolean stopped;

        private SynchronousConsumerRun(GooglePubsubConstants.AckMode ackMode, Processor processor) throws Exception {
            component.setCamelContext(context);
            context.start();
            endpoint = spy(new CustomGooglePubSubEndpoint("google-pubsub:project-id:subscription-name", component));
            endpoint.setProjectId("project-id");
            endpoint.setDestinationName("subscription-name");
            endpoint.setConcurrentConsumers(1);
            endpoint.setSynchronousPull(true);
            endpoint.setMaxMessagesPerPoll(7);
            endpoint.setAckMode(ackMode);
            consumer = new CustomGooglePubSubConsumer(endpoint, processor);
            consumer.setExceptionHandler(exceptionHandler);
            doReturn(executor).when(endpoint).createExecutor(consumer);
            doAnswer(invocation -> {
                creationAttempts.incrementAndGet();
                if (creationFailures.getAndDecrement() > 0) {
                    throw new IOException("Subscriber creation failed");
                }
                return stub;
            }).when(component).getSubscriberStub(endpoint);
            when(stub.pullCallable()).thenReturn(pull);
            when(pull.futureCall(any(PullRequest.class))).thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                ApiFuture<PullResponse> response = responses.poll();
                if (response == null) {
                    throw new AssertionError("Synchronous consumer requested an unexpected pull.");
                }
                return response;
            });
        }

        private ApiFuture<PullResponse> pendingResponse() throws Exception {
            ApiFuture<PullResponse> pending = mock(ApiFuture.class);
            SettableApiFuture<PullResponse> delegate = SettableApiFuture.create();
            doAnswer(invocation -> {
                pendingEntered.countDown();
                return delegate.get();
            }).when(pending).get();
            doAnswer(invocation -> delegate.cancel(invocation.getArgument(0))).when(pending).cancel(anyBoolean());
            return pending;
        }

        private void start() {
            consumer.start();
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(executor).submit(task.capture());
            running = worker.submit(task.getValue());
        }

        private void awaitPending() throws Exception {
            assertTrue(pendingEntered.await(5, TimeUnit.SECONDS), "Synchronous consumer did not start its next pull.");
        }

        private void awaitWorker() throws Exception {
            running.get(5, TimeUnit.SECONDS);
        }

        private void stop() throws Exception {
            if (!stopped) {
                doReturn(null).when(endpoint).getCamelContext();
                consumer.stop();
                stopped = true;
                awaitWorker();
            }
        }

        @Override
        public void close() throws Exception {
            try {
                stop();
            } finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS), "Synchronous test worker did not stop.");
                context.close();
            }
        }
    }
}
