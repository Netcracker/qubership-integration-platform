package org.qubership.integration.platform.engine.routes.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PubSubConnectionProxy implements AutoCloseable {
    private final Object lock = new Object();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Connection> connections = new LinkedHashSet<>();
    private final List<String> listenerFailures = new ArrayList<>();
    private volatile boolean closed;
    private ServerSocket listener;
    private InetSocketAddress upstreamAddress;
    private Outage outage;

    void start(String upstreamHost, int upstreamPort) throws IOException {
        upstreamAddress = new InetSocketAddress(upstreamHost, upstreamPort);
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        workers.execute(this::acceptConnections);
    }

    int port() {
        return listener.getLocalPort();
    }

    void disconnectOnNextClientWrite() {
        synchronized (lock) {
            assertFalse(connections.isEmpty(), "Pub/Sub disconnect requires an established TCP connection.");
            outage = new Outage(Set.copyOf(connections));
        }
    }

    void verifyOutage() {
        synchronized (lock) {
            assertTrue(listenerFailures.isEmpty(), () -> String.join("\n", listenerFailures));
            assertNotNull(outage, "Pub/Sub connection outage was not armed.");
            assertEquals(1, outage.resets, "Pub/Sub proxy did not reset the connection during a client write.");
            assertTrue(outage.establishedResets > 0, "Pub/Sub proxy did not reset a previously established connection.");
            assertEquals(1, outage.rejectedConnections, "Pub/Sub proxy did not reject the first reconnection.");
            assertTrue(outage.recoveredConnections > 0, "Pub/Sub client did not establish a new connection.");
            assertTrue(outage.forwardedBytes > 0, "Pub/Sub client did not send data through the recovered connection.");
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        try {
            if (listener != null) {
                listener.close();
            }
        } finally {
            List<Connection> openConnections;
            synchronized (lock) {
                openConnections = List.copyOf(connections);
            }
            openConnections.forEach(connection -> connection.close(false));
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Pub/Sub TCP proxy workers did not stop.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the Pub/Sub TCP proxy.", exception);
            }
        }
    }

    private void acceptConnections() {
        while (!closed) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException exception) {
                if (!closed) {
                    synchronized (lock) {
                        listenerFailures.add("Pub/Sub TCP proxy could not accept a connection: " + exception.getMessage());
                    }
                }
                return;
            }
            Socket upstream = new Socket();
            try {
                upstream.connect(upstreamAddress, 5000);
                Connection connection;
                synchronized (lock) {
                    if (closed) {
                        closeSocket(client, false);
                        closeSocket(upstream, false);
                        return;
                    }
                    if (outage != null && !outage.armed && outage.rejectedConnections == 0) {
                        outage.rejectedConnections++;
                        closeSocket(client, true);
                        closeSocket(upstream, false);
                        continue;
                    }
                    Outage recovery = outage != null && !outage.armed ? outage : null;
                    connection = new Connection(client, upstream, recovery);
                    connections.add(connection);
                    if (recovery != null) {
                        recovery.recoveredConnections++;
                    }
                }
                workers.execute(() -> connection.forward(true));
                workers.execute(() -> connection.forward(false));
            } catch (IOException exception) {
                closeSocket(client, false);
                closeSocket(upstream, false);
                if (!closed) {
                    synchronized (lock) {
                        listenerFailures.add("Pub/Sub TCP proxy could not connect upstream: " + exception.getMessage());
                    }
                }
            }
        }
    }

    private boolean resetOnClientWrite() {
        List<Connection> resetConnections;
        synchronized (lock) {
            if (outage == null || !outage.armed) {
                return false;
            }
            outage.armed = false;
            outage.resets++;
            resetConnections = List.copyOf(connections);
            outage.establishedResets = (int) resetConnections.stream()
                    .filter(outage.establishedConnections::contains)
                    .count();
        }
        resetConnections.forEach(existing -> existing.close(true));
        return true;
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
        private final Socket client;
        private final Socket upstream;
        private final Outage recovery;
        private final AtomicBoolean connectionClosed = new AtomicBoolean();

        private Connection(Socket client, Socket upstream, Outage recovery) {
            this.client = client;
            this.upstream = upstream;
            this.recovery = recovery;
        }

        private void forward(boolean fromClient) {
            try {
                InputStream input = (fromClient ? client : upstream).getInputStream();
                OutputStream output = (fromClient ? upstream : client).getOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    // Drop the next client bytes, including sends on a new pooled connection.
                    if (fromClient && resetOnClientWrite()) {
                        return;
                    }
                    output.write(buffer, 0, read);
                    if (fromClient && recovery != null) {
                        synchronized (lock) {
                            recovery.forwardedBytes += read;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Socket resets and peer shutdown end both directions of the connection.
            } finally {
                close(false);
            }
        }

        private void close(boolean reset) {
            if (connectionClosed.compareAndSet(false, true)) {
                synchronized (lock) {
                    connections.remove(this);
                }
                closeSocket(client, reset);
                closeSocket(upstream, reset);
            }
        }
    }

    private static final class Outage {
        private final Set<Connection> establishedConnections;
        private boolean armed = true;
        private int resets;
        private int establishedResets;
        private int rejectedConnections;
        private int recoveredConnections;
        private long forwardedBytes;

        private Outage(Set<Connection> establishedConnections) {
            this.establishedConnections = establishedConnections;
        }
    }
}
