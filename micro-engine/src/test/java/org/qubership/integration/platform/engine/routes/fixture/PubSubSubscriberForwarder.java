package org.qubership.integration.platform.engine.routes.fixture;

import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.pubsub.v1.Subscription;
import io.grpc.CallOptions;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class PubSubSubscriberForwarder implements AutoCloseable {
    static final String SERVICE_NAME = "google.pubsub.v1.Subscriber";
    static final MethodDescriptor<StreamingPullRequest, StreamingPullResponse> STREAMING_PULL = method(
            "StreamingPull", MethodDescriptor.MethodType.BIDI_STREAMING,
            StreamingPullRequest.getDefaultInstance(), StreamingPullResponse.getDefaultInstance());
    static final MethodDescriptor<PullRequest, PullResponse> PULL = method(
            "Pull", MethodDescriptor.MethodType.UNARY, PullRequest.getDefaultInstance(), PullResponse.getDefaultInstance());
    static final MethodDescriptor<AcknowledgeRequest, Empty> ACKNOWLEDGE = method(
            "Acknowledge", MethodDescriptor.MethodType.UNARY, AcknowledgeRequest.getDefaultInstance(), Empty.getDefaultInstance());
    static final MethodDescriptor<ModifyAckDeadlineRequest, Empty> MODIFY_ACK_DEADLINE = method(
            "ModifyAckDeadline", MethodDescriptor.MethodType.UNARY,
            ModifyAckDeadlineRequest.getDefaultInstance(), Empty.getDefaultInstance());
    static final MethodDescriptor<GetSubscriptionRequest, Subscription> GET_SUBSCRIPTION = method(
            "GetSubscription", MethodDescriptor.MethodType.UNARY,
            GetSubscriptionRequest.getDefaultInstance(), Subscription.getDefaultInstance());

    private final Object lock = new Object();
    private final ManagedChannel upstreamChannel;
    private final Set<ForwardingStream> streams = new HashSet<>();
    private final Set<Context.CancellableContext> unaryCalls = ConcurrentHashMap.newKeySet();
    private final Map<String, String> messageIdsByAckId = new HashMap<>();
    private final Map<String, Timestamp> publishTimesByMessageId = new HashMap<>();
    private final Map<String, Integer> remainingDeliveriesByMessageId = new HashMap<>();
    private final Set<String> acknowledgedMessageIds = new HashSet<>();
    private final Set<String> negativeAcknowledgedMessageIds = new HashSet<>();
    private long streamingPullRequests;
    private long acknowledgedCount;
    private long negativeAcknowledgedCount;
    private int maxDeliveriesPerMessage = Integer.MAX_VALUE;

    PubSubSubscriberForwarder(ManagedChannel upstreamChannel) {
        this.upstreamChannel = upstreamChannel;
    }

    ServerServiceDefinition service() {
        return ServerServiceDefinition.builder(SERVICE_NAME)
                .addMethod(STREAMING_PULL, ServerCalls.asyncBidiStreamingCall(this::streamingPull))
                .addMethod(PULL, ServerCalls.asyncUnaryCall((request, observer) ->
                        forwardUnary(PULL, request, observer, response -> observeDelivery(response.getReceivedMessagesList()))))
                .addMethod(ACKNOWLEDGE, ServerCalls.asyncUnaryCall((request, observer) ->
                        forwardUnary(ACKNOWLEDGE, request, observer, response -> observeAcks(request.getAckIdsList(), false))))
                .addMethod(MODIFY_ACK_DEADLINE, ServerCalls.asyncUnaryCall((request, observer) ->
                        forwardUnary(MODIFY_ACK_DEADLINE, request, observer, response -> {
                            if (request.getAckDeadlineSeconds() == 0) {
                                observeAcks(request.getAckIdsList(), true);
                            }
                        })))
                .addMethod(GET_SUBSCRIPTION, ServerCalls.asyncUnaryCall((request, observer) ->
                        forwardUnary(GET_SUBSCRIPTION, request, observer, response -> { })))
                .build();
    }

    Statistics statistics() {
        synchronized (lock) {
            return new Statistics(streamingPullRequests, acknowledgedCount, negativeAcknowledgedCount,
                    Set.copyOf(acknowledgedMessageIds), Set.copyOf(negativeAcknowledgedMessageIds), streams.size());
        }
    }

    Timestamp publishTime(String messageId) {
        synchronized (lock) {
            return publishTimesByMessageId.get(messageId);
        }
    }

    void maxDeliveriesPerMessage(int maximum) {
        synchronized (lock) {
            maxDeliveriesPerMessage = maximum;
        }
    }

    @Override
    public void close() {
        List<ForwardingStream> openStreams;
        synchronized (lock) {
            openStreams = List.copyOf(streams);
        }
        openStreams.forEach(ForwardingStream::cancel);
        unaryCalls.forEach(context -> context.cancel(null));
    }

    private StreamObserver<StreamingPullRequest> streamingPull(StreamObserver<StreamingPullResponse> observer) {
        ForwardingStream stream = new ForwardingStream(observer);
        synchronized (lock) {
            streamingPullRequests++;
            streams.add(stream);
        }
        stream.start();
        return stream;
    }

    private <Q, R> void forwardUnary(MethodDescriptor<Q, R> method, Q request, StreamObserver<R> observer,
                                     Consumer<R> observation) {
        Context.CancellableContext context = Context.current().withCancellation();
        unaryCalls.add(context);
        context.run(() -> ClientCalls.asyncUnaryCall(upstreamChannel.newCall(method,
                CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS)), request, new StreamObserver<>() {
                    @Override
                    public void onNext(R response) {
                        observation.accept(response);
                        observer.onNext(response);
                    }

                    @Override
                    public void onError(Throwable failure) {
                        finish();
                        observer.onError(failure);
                    }

                    @Override
                    public void onCompleted() {
                        finish();
                        observer.onCompleted();
                    }

                    private void finish() {
                        unaryCalls.remove(context);
                        context.cancel(null);
                    }
                }));
    }

    private void observeDelivery(List<ReceivedMessage> messages) {
        synchronized (lock) {
            for (ReceivedMessage message : messages) {
                messageIdsByAckId.put(message.getAckId(), message.getMessage().getMessageId());
                publishTimesByMessageId.put(message.getMessage().getMessageId(), message.getMessage().getPublishTime());
            }
        }
    }

    private StreamingPullResponse permittedDeliveries(StreamingPullResponse response) {
        synchronized (lock) {
            StreamingPullResponse.Builder permitted = response.toBuilder().clearReceivedMessages();
            for (ReceivedMessage message : response.getReceivedMessagesList()) {
                String id = message.getMessage().getMessageId();
                int remaining = remainingDeliveriesByMessageId.computeIfAbsent(id, ignored -> maxDeliveriesPerMessage);
                if (remaining > 0) {
                    remainingDeliveriesByMessageId.put(id, remaining - 1);
                    permitted.addReceivedMessages(message);
                }
            }
            return permitted.build();
        }
    }

    private void observeAcks(List<String> ackIds, boolean negative) {
        synchronized (lock) {
            if (negative) {
                negativeAcknowledgedCount += ackIds.size();
            } else {
                acknowledgedCount += ackIds.size();
            }
            Set<String> messageIds = negative ? negativeAcknowledgedMessageIds : acknowledgedMessageIds;
            for (String ackId : ackIds) {
                String messageId = messageIdsByAckId.remove(ackId);
                if (messageId != null) {
                    messageIds.add(messageId);
                }
            }
        }
    }

    private void observeRequest(StreamingPullRequest request) {
        observeAcks(request.getAckIdsList(), false);
        for (int index = 0; index < request.getModifyDeadlineAckIdsCount(); index++) {
            if (request.getModifyDeadlineSeconds(index) == 0) {
                observeAcks(List.of(request.getModifyDeadlineAckIds(index)), true);
            }
        }
    }

    private static <Q extends Message, R extends Message> MethodDescriptor<Q, R> method(
            String name, MethodDescriptor.MethodType type, Q request, R response) {
        return MethodDescriptor.<Q, R>newBuilder()
                .setType(type)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, name))
                .setRequestMarshaller(ProtoUtils.marshaller(request))
                .setResponseMarshaller(ProtoUtils.marshaller(response))
                .build();
    }

    private final class ForwardingStream implements StreamObserver<StreamingPullRequest> {
        private final ServerCallStreamObserver<StreamingPullResponse> downstream;
        private final Context.CancellableContext context = Context.current().withCancellation();
        private final AtomicBoolean finished = new AtomicBoolean();
        private ClientCallStreamObserver<StreamingPullRequest> upstream;
        private boolean requestPending;
        private boolean responsePending = true;
        private boolean halfClosed;

        private ForwardingStream(StreamObserver<StreamingPullResponse> observer) {
            downstream = (ServerCallStreamObserver<StreamingPullResponse>) observer;
            downstream.disableAutoRequest();
            downstream.setOnCancelHandler(this::cancel);
            downstream.setOnReadyHandler(this::requestResponse);
        }

        private void start() {
            context.run(() -> ClientCalls.asyncBidiStreamingCall(upstreamChannel.newCall(STREAMING_PULL, CallOptions.DEFAULT),
                    new ClientResponseObserver<StreamingPullRequest, StreamingPullResponse>() {
                        @Override
                        public void beforeStart(ClientCallStreamObserver<StreamingPullRequest> requestStream) {
                            upstream = requestStream;
                            upstream.disableAutoRequestWithInitial(1);
                            upstream.setOnReadyHandler(ForwardingStream.this::requestMessage);
                        }

                        @Override
                        public void onNext(StreamingPullResponse response) {
                            StreamingPullResponse permitted = permittedDeliveries(response);
                            observeDelivery(permitted.getReceivedMessagesList());
                            downstream.onNext(permitted);
                            synchronized (ForwardingStream.this) {
                                responsePending = false;
                            }
                            requestResponse();
                        }

                        @Override
                        public void onError(Throwable failure) {
                            if (finish() && !downstream.isCancelled()) {
                                downstream.onError(failure);
                            }
                        }

                        @Override
                        public void onCompleted() {
                            if (finish() && !downstream.isCancelled()) {
                                downstream.onCompleted();
                            }
                        }
                    }));
        }

        @Override
        public synchronized void onNext(StreamingPullRequest request) {
            requestPending = false;
            if (!finished.get()) {
                upstream.onNext(request);
                observeRequest(request);
                requestMessage();
            }
        }

        @Override
        public void onError(Throwable failure) {
            cancel();
        }

        @Override
        public synchronized void onCompleted() {
            halfClosed = true;
            if (!finished.get()) {
                upstream.onCompleted();
            }
        }

        private synchronized void requestMessage() {
            if (!finished.get() && !halfClosed && !requestPending && upstream != null && upstream.isReady()) {
                requestPending = true;
                downstream.request(1);
            }
        }

        private synchronized void requestResponse() {
            if (!finished.get() && !responsePending && upstream != null && downstream.isReady()) {
                responsePending = true;
                upstream.request(1);
            }
        }

        private void cancel() {
            if (finish() && upstream != null) {
                upstream.cancel("Pub/Sub consumer disconnected", Status.CANCELLED.asRuntimeException());
            }
        }

        private boolean finish() {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            synchronized (lock) {
                streams.remove(this);
            }
            context.cancel(null);
            return true;
        }
    }

    record Statistics(long streamingPullRequests, long acknowledgedCount, long negativeAcknowledgedCount,
                      Set<String> acknowledgedMessageIds, Set<String> negativeAcknowledgedMessageIds, int activeStreams) {
    }
}
