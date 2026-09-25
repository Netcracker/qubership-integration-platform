package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ByteBufferAccessor;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.ResponseHeader;
import org.apache.kafka.common.requests.SaslAuthenticateRequest;
import org.apache.kafka.common.requests.SaslAuthenticateResponse;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KafkaConnectionProxy implements AutoCloseable {
    private final Object lock = new Object();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Connection> connections = new LinkedHashSet<>();
    private final List<String> failures = new ArrayList<>();
    private final Map<Short, Long> produceRequestsByAcks = new LinkedHashMap<>();
    private final Map<String, Long> recordBatchesByCompression = new LinkedHashMap<>();
    private volatile boolean closed;
    private ServerSocket listener;
    private InetSocketAddress upstreamAddress;
    private SSLContext serverTlsContext;
    private SSLContext upstreamTlsContext;
    private boolean unavailable;
    private boolean disconnectBeforeProduce;
    private boolean remainUnavailableAfterProduce;
    private int responsesToDrop;
    private long produceAttempts;
    private long forwardedProduceRequests;
    private long successfulProduceResponses;
    private long droppedProduceResponses;
    private long disconnectedProduceRequests;
    private long rejectedConnections;
    private long connectionResets;
    private long successfulAuthentications;
    private long failedAuthentications;
    private long authenticatedProduceRequests;
    private long successfulTlsHandshakes;
    private long failedTlsHandshakes;
    private long tlsProduceRequests;

    void start() throws IOException {
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
    }

    int port() {
        return listener.getLocalPort();
    }

    void connectTo(String upstreamHost, int upstreamPort) {
        upstreamAddress = new InetSocketAddress(upstreamHost, upstreamPort);
        workers.execute(this::acceptConnections);
    }

    void configureTls(SSLContext serverContext, SSLContext upstreamContext) {
        synchronized (lock) {
            assertTrue(upstreamAddress == null, "Configure Kafka proxy TLS before connecting to the broker.");
            serverTlsContext = serverContext;
            upstreamTlsContext = upstreamContext;
        }
    }

    void rotateTlsCertificate(SSLContext serverContext) {
        List<Connection> openConnections;
        synchronized (lock) {
            assertTrue(serverTlsContext != null, "Kafka proxy TLS must be configured before rotating its certificate.");
            serverTlsContext = serverContext;
            openConnections = List.copyOf(connections);
        }
        openConnections.forEach(connection -> connection.close(true));
    }

    void disconnectBeforeNextProduce() {
        disconnectBeforeNextProduce(false);
    }

    void disconnectBeforeNextProduce(boolean remainUnavailable) {
        synchronized (lock) {
            assertFalse(disconnectBeforeProduce, "A Kafka Produce disconnect is already armed.");
            disconnectBeforeProduce = true;
            remainUnavailableAfterProduce = remainUnavailable;
        }
    }

    void disconnect() {
        List<Connection> openConnections;
        synchronized (lock) {
            unavailable = true;
            openConnections = List.copyOf(connections);
        }
        openConnections.forEach(connection -> connection.close(true));
    }

    void restore() {
        synchronized (lock) {
            unavailable = false;
        }
    }

    void dropNextProduceResponses(int count) {
        synchronized (lock) {
            assertTrue(count > 0, "The number of Kafka Produce responses to drop must be positive.");
            assertEquals(0, responsesToDrop, "Kafka Produce response loss is already armed.");
            responsesToDrop = count;
        }
    }

    Statistics statistics() {
        synchronized (lock) {
            return new Statistics(produceAttempts, forwardedProduceRequests, successfulProduceResponses,
                    droppedProduceResponses, disconnectedProduceRequests, rejectedConnections, connectionResets,
                    successfulAuthentications, failedAuthentications, authenticatedProduceRequests,
                    successfulTlsHandshakes, failedTlsHandshakes, tlsProduceRequests,
                    Map.copyOf(produceRequestsByAcks), Map.copyOf(recordBatchesByCompression));
        }
    }

    short observeProduceRequest(byte[] frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        RequestHeader header = RequestHeader.parse(buffer);
        ProduceRequest request = ProduceRequest.parse(new ByteBufferAccessor(buffer), header.apiVersion());
        Map<String, Long> compressionCounts = new LinkedHashMap<>();
        request.data().topicData().forEach(topic -> topic.partitionData().forEach(partition -> {
            for (RecordBatch batch : ((Records) partition.records()).batches()) {
                compressionCounts.merge(batch.compressionType().name, 1L, Long::sum);
            }
        }));
        synchronized (lock) {
            // Count requests received from the producer, including attempts disconnected before forwarding.
            produceAttempts++;
            produceRequestsByAcks.merge(request.acks(), 1L, Long::sum);
            compressionCounts.forEach((codec, count) -> recordBatchesByCompression.merge(codec, count, Long::sum));
            lock.notifyAll();
        }
        return request.acks();
    }

    void awaitProduceRequests(long count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (lock) {
            while (produceAttempts < count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
        }
    }

    void awaitFailedTlsHandshakes(long count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (lock) {
            while (failedTlsHandshakes < count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
        }
    }

    void awaitAuthenticatedProduceRequests(long count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (lock) {
            while (authenticatedProduceRequests < count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
        }
    }

    void verify() {
        synchronized (lock) {
            assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
            assertFalse(disconnectBeforeProduce, "Kafka did not send a Produce request for the armed disconnect.");
            assertEquals(0, responsesToDrop, "Kafka did not return enough successful Produce responses to drop.");
        }
    }

    @Override
    public void close() throws IOException {
        List<Connection> openConnections;
        synchronized (lock) {
            closed = true;
            openConnections = List.copyOf(connections);
        }
        try {
            if (listener != null) {
                listener.close();
            }
        } finally {
            openConnections.forEach(connection -> connection.close(false));
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Kafka TCP proxy workers did not stop.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the Kafka TCP proxy.", exception);
            }
        }
    }

    private void acceptConnections() {
        while (!closed) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException exception) {
                recordFailureUnlessClosed("Kafka TCP proxy could not accept a connection", exception);
                return;
            }
            synchronized (lock) {
                if (closed) {
                    closeSocket(client, false);
                    return;
                }
                if (unavailable) {
                    rejectedConnections++;
                    connectionResets++;
                    closeSocket(client, true);
                    continue;
                }
                Connection connection = new Connection(client, serverTlsContext, upstreamTlsContext);
                connections.add(connection);
                workers.execute(connection::connect);
            }
        }
    }

    private void recordFailureUnlessClosed(String description, Exception exception) {
        synchronized (lock) {
            if (!closed) {
                failures.add(description + ": " + exception);
            }
        }
    }

    private boolean shouldDisconnectProduce() {
        boolean permanentDisconnect;
        synchronized (lock) {
            if (!disconnectBeforeProduce) {
                return false;
            }
            disconnectBeforeProduce = false;
            disconnectedProduceRequests++;
            permanentDisconnect = remainUnavailableAfterProduce;
            remainUnavailableAfterProduce = false;
            if (permanentDisconnect) {
                unavailable = true;
            }
        }
        if (permanentDisconnect) {
            disconnect();
        }
        return true;
    }

    private boolean shouldDropProduceResponse(byte[] frame, short version) {
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        ResponseHeader.parse(buffer, ApiKeys.PRODUCE.responseHeaderVersion(version));
        ProduceResponse response = ProduceResponse.parse(new ByteBufferAccessor(buffer), version);
        Map<Errors, Integer> errors = response.errorCounts();
        boolean accepted = !errors.isEmpty() && errors.keySet().equals(Set.of(Errors.NONE));
        synchronized (lock) {
            if (accepted) {
                successfulProduceResponses++;
                if (responsesToDrop > 0) {
                    responsesToDrop--;
                    droppedProduceResponses++;
                    return true;
                }
            }
            return false;
        }
    }

    private static byte[] readFrame(DataInputStream input) throws IOException {
        int length = input.readInt();
        byte[] frame = input.readNBytes(length);
        if (frame.length != length) {
            throw new EOFException("Kafka connection closed before the complete frame arrived.");
        }
        return frame;
    }

    private static void writeFrame(DataOutputStream output, byte[] frame) throws IOException {
        output.writeInt(frame.length);
        output.write(frame);
        output.flush();
    }

    private static void closeSocket(Socket socket, boolean reset) {
        try {
            if (reset) {
                socket.setSoLinger(true, 0);
            }
        } catch (IOException ignored) {
            // The peer may have closed its connection before the reset.
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Both forwarding tasks share the same sockets during shutdown.
            }
        }
    }

    private final class Connection {
        private final Socket clientTransport;
        private final Socket upstreamTransport = new Socket();
        private final SSLContext clientTlsContext;
        private final SSLContext brokerTlsContext;
        private volatile Socket client;
        private volatile Socket upstream = upstreamTransport;
        private final AtomicBoolean connectionClosed = new AtomicBoolean();
        private final Map<Integer, Short> pendingProduceRequests = new ConcurrentHashMap<>();
        private final Map<Integer, Short> pendingAuthentications = new ConcurrentHashMap<>();
        private volatile boolean authenticated;
        private boolean tlsEstablished;

        private Connection(Socket client, SSLContext clientTlsContext, SSLContext brokerTlsContext) {
            this.clientTransport = client;
            this.client = client;
            this.clientTlsContext = clientTlsContext;
            this.brokerTlsContext = brokerTlsContext;
        }

        private void connect() {
            try {
                if (clientTlsContext != null && !acceptTlsClient()) {
                    return;
                }
                if (connectionClosed.get()) {
                    return;
                }
                upstreamTransport.connect(upstreamAddress, 5000);
                if (brokerTlsContext != null) {
                    connectTlsBroker();
                }
                synchronized (lock) {
                    if (!closed && !connectionClosed.get()) {
                        workers.execute(this::forwardRequests);
                        workers.execute(this::forwardResponses);
                    }
                }
            } catch (IOException | RuntimeException exception) {
                if (!connectionClosed.get()) {
                    recordFailureUnlessClosed("Kafka TCP proxy could not connect upstream", exception);
                }
                close(false);
            }
        }

        private boolean acceptTlsClient() throws IOException {
            SSLSocket tlsClient = (SSLSocket) clientTlsContext.getSocketFactory().createSocket(
                    clientTransport, clientTransport.getInetAddress().getHostAddress(), clientTransport.getPort(), true);
            synchronized (lock) {
                if (closed || connectionClosed.get()) {
                    closeSocket(tlsClient, false);
                    return false;
                }
                client = tlsClient;
            }
            tlsClient.setUseClientMode(false);
            try {
                tlsClient.setSoTimeout(5000);
                tlsClient.startHandshake();
                tlsClient.setSoTimeout(0);
                synchronized (lock) {
                    if (closed || connectionClosed.get()) {
                        return false;
                    }
                    successfulTlsHandshakes++;
                    tlsEstablished = true;
                }
                return true;
            } catch (IOException exception) {
                synchronized (lock) {
                    if (!closed && !connectionClosed.get()) {
                        failedTlsHandshakes++;
                        lock.notifyAll();
                    }
                }
                close(false);
                return false;
            }
        }

        private void connectTlsBroker() throws IOException {
            SSLSocket tlsBroker = (SSLSocket) brokerTlsContext.getSocketFactory().createSocket(
                    upstreamTransport, upstreamAddress.getHostString(), upstreamAddress.getPort(), true);
            synchronized (lock) {
                if (closed || connectionClosed.get()) {
                    closeSocket(tlsBroker, false);
                    return;
                }
                upstream = tlsBroker;
            }
            SSLParameters parameters = tlsBroker.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tlsBroker.setSSLParameters(parameters);
            tlsBroker.setSoTimeout(5000);
            tlsBroker.startHandshake();
            tlsBroker.setSoTimeout(0);
        }

        private void forwardRequests() {
            try {
                DataInputStream input = new DataInputStream(client.getInputStream());
                DataOutputStream output = new DataOutputStream(upstream.getOutputStream());
                while (!connectionClosed.get()) {
                    byte[] frame = readFrame(input);
                    ByteBuffer header = ByteBuffer.wrap(frame);
                    short apiKey = header.getShort();
                    short version = header.getShort();
                    int correlationId = header.getInt();
                    if (apiKey == ApiKeys.SASL_AUTHENTICATE.id) {
                        observeAuthenticationRequest(frame, version, correlationId);
                    }
                    boolean produce = apiKey == ApiKeys.PRODUCE.id;
                    if (produce) {
                        short requiredAcks = observeProduceRequest(frame);
                        if (shouldDisconnectProduce()) {
                            close(true);
                            return;
                        }
                        if (requiredAcks != 0) {
                            pendingProduceRequests.put(correlationId, version);
                        }
                    }
                    writeFrame(output, frame);
                    if (produce) {
                        synchronized (lock) {
                            forwardedProduceRequests++;
                            if (authenticated) {
                                authenticatedProduceRequests++;
                                if (tlsEstablished) {
                                    tlsProduceRequests++;
                                }
                                lock.notifyAll();
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // A peer shutdown or an injected reset closes both directions.
            } catch (RuntimeException exception) {
                recordFailureUnlessClosed("Kafka TCP proxy could not decode a request", exception);
            } finally {
                close(false);
            }
        }

        private void forwardResponses() {
            try {
                DataInputStream input = new DataInputStream(upstream.getInputStream());
                DataOutputStream output = new DataOutputStream(client.getOutputStream());
                while (!connectionClosed.get()) {
                    byte[] frame = readFrame(input);
                    int correlationId = ByteBuffer.wrap(frame).getInt();
                    Short authenticationVersion = pendingAuthentications.remove(correlationId);
                    if (authenticationVersion != null) {
                        observeAuthenticationResponse(frame, authenticationVersion);
                    }
                    Short produceVersion = pendingProduceRequests.remove(correlationId);
                    if (produceVersion != null && shouldDropProduceResponse(frame, produceVersion)) {
                        close(true);
                        return;
                    }
                    writeFrame(output, frame);
                }
            } catch (IOException ignored) {
                // A peer shutdown or an injected reset closes both directions.
            } catch (RuntimeException exception) {
                recordFailureUnlessClosed("Kafka TCP proxy could not decode a response", exception);
            } finally {
                close(false);
            }
        }

        private void observeAuthenticationRequest(byte[] frame, short version, int correlationId) {
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            RequestHeader.parse(buffer);
            byte[] credentials = SaslAuthenticateRequest.parse(new ByteBufferAccessor(buffer), version)
                    .data().authBytes();
            int usernameStart = 0;
            while (usernameStart < credentials.length && credentials[usernameStart] != 0) {
                usernameStart++;
            }
            usernameStart++;
            int usernameEnd = usernameStart;
            while (usernameEnd < credentials.length && credentials[usernameEnd] != 0) {
                usernameEnd++;
            }
            String username = new String(credentials, usernameStart, usernameEnd - usernameStart, StandardCharsets.UTF_8);
            if (!KafkaSaslSupport.ADMIN_USERNAME.equals(username)) {
                authenticated = false;
                pendingAuthentications.put(correlationId, version);
            }
        }

        private void observeAuthenticationResponse(byte[] frame, short version) {
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            ResponseHeader.parse(buffer, ApiKeys.SASL_AUTHENTICATE.responseHeaderVersion(version));
            SaslAuthenticateResponse response = SaslAuthenticateResponse.parse(new ByteBufferAccessor(buffer), version);
            authenticated = response.error() == Errors.NONE;
            synchronized (lock) {
                if (authenticated) {
                    successfulAuthentications++;
                } else {
                    failedAuthentications++;
                }
            }
        }

        private void close(boolean reset) {
            if (connectionClosed.compareAndSet(false, true)) {
                synchronized (lock) {
                    connections.remove(this);
                    if (reset) {
                        connectionResets++;
                    }
                }
                closeSocket(clientTransport, reset);
                closeSocket(upstreamTransport, reset);
                if (client != clientTransport) {
                    closeSocket(client, false);
                }
                if (upstream != upstreamTransport) {
                    closeSocket(upstream, false);
                }
            }
        }
    }

    record Statistics(
            long produceAttempts,
            long forwardedProduceRequests,
            long successfulProduceResponses,
            long droppedProduceResponses,
            long disconnectedProduceRequests,
            long rejectedConnections,
            long connectionResets,
            long successfulAuthentications,
            long failedAuthentications,
            long authenticatedProduceRequests,
            long successfulTlsHandshakes,
            long failedTlsHandshakes,
            long tlsProduceRequests,
            Map<Short, Long> produceRequestsByAcks,
            Map<String, Long> recordBatchesByCompression
    ) {
    }
}
