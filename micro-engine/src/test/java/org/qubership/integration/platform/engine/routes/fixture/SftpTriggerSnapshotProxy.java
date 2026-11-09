package org.qubership.integration.platform.engine.routes.fixture;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class SftpTriggerSnapshotProxy implements AutoCloseable {
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Link> links = ConcurrentHashMap.newKeySet();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong disconnected = new AtomicLong();
    private final AtomicLong serverBytes = new AtomicLong();
    private final AtomicLong interruptions = new AtomicLong();
    private final AtomicLong interruptedBytes = new AtomicLong();
    private final AtomicReference<ByteBudget> interruption = new AtomicReference<>();
    private final int port;
    private volatile InetSocketAddress target;
    private volatile ServerSocket listener;
    private volatile boolean closed;
    private volatile IOException failure;

    SftpTriggerSnapshotProxy(String host, int targetPort) throws IOException {
        target = new InetSocketAddress(host, targetPort);
        ServerSocket socket = bind(0);
        port = socket.getLocalPort();
        listener = socket;
        workers.execute(() -> accept(socket));
    }

    int port() {
        return port;
    }

    synchronized void listenerAvailable(boolean available) throws IOException {
        if (available && listener == null) {
            ServerSocket socket = bind(port);
            listener = socket;
            workers.execute(() -> accept(socket));
        } else if (!available && listener != null) {
            ServerSocket socket = listener;
            listener = null;
            socket.close();
            disconnect();
        }
    }

    void target(String host, int targetPort) {
        target = new InetSocketAddress(host, targetPort);
        disconnect();
    }

    void disconnect() {
        List.copyOf(links).forEach(Link::close);
    }

    void interruptAfterServerBytes(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("SFTP interruption threshold must be positive.");
        }
        assertTrue(interruption.compareAndSet(null, new ByteBudget(threshold)),
                "An SFTP transfer interruption is already armed.");
    }

    long interruptionCount() {
        return interruptions.get();
    }

    long interruptedServerBytes() {
        return interruptedBytes.get();
    }

    long forwardedServerBytes() {
        return serverBytes.get();
    }

    long acceptedConnections() {
        return accepted.get();
    }

    long closedConnections() {
        return disconnected.get();
    }

    int activeConnections() {
        return links.size();
    }

    void verify() {
        assertTrue(failure == null, () -> "SFTP forwarding listener failed: " + failure);
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("127.0.0.1", port));
        return socket;
    }

    private void accept(ServerSocket socket) {
        while (!closed && listener == socket) {
            Socket client = null;
            Socket upstream = null;
            try {
                client = socket.accept();
                upstream = new Socket();
                upstream.connect(target, 5000);
                synchronized (this) {
                    if (closed || listener != socket) {
                        closeSocket(client);
                        closeSocket(upstream);
                    } else {
                        Link link = new Link(client, upstream);
                        links.add(link);
                        accepted.incrementAndGet();
                        workers.execute(() -> link.forward(link.client, link.upstream, false));
                        workers.execute(() -> link.forward(link.upstream, link.client, true));
                    }
                }
            } catch (IOException exception) {
                closeSocket(client);
                closeSocket(upstream);
                if (!closed && listener == socket) {
                    failure = exception;
                }
            }
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Both forwarding directions close the same socket pair.
            }
        }
    }

    @Override
    public void close() throws Exception {
        ServerSocket socket;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            socket = listener;
            listener = null;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } finally {
            disconnect();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "SFTP forwarding workers did not stop.");
        }
        verify();
    }

    private final class Link {
        private final Socket client;
        private final Socket upstream;
        private final AtomicBoolean linkClosed = new AtomicBoolean();

        private Link(Socket client, Socket upstream) {
            this.client = client;
            this.upstream = upstream;
        }

        private void forward(Socket source, Socket destination, boolean serverToClient) {
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = source.getInputStream().read(buffer)) >= 0) {
                    if (serverToClient) {
                        if (forwardServerBytes(buffer, count, destination)) {
                            close();
                            interruptions.incrementAndGet();
                            return;
                        }
                    } else {
                        destination.getOutputStream().write(buffer, 0, count);
                    }
                }
            } catch (IOException ignored) {
                // Route shutdown and transport controls close established SSH connections.
            } finally {
                close();
            }
        }

        private boolean forwardServerBytes(byte[] buffer, int count, Socket destination) throws IOException {
            ByteBudget budget = interruption.get();
            if (budget != null) {
                synchronized (budget) {
                    if (interruption.get() == budget) {
                        int allowed = (int) Math.min(count, budget.remaining);
                        destination.getOutputStream().write(buffer, 0, allowed);
                        serverBytes.addAndGet(allowed);
                        budget.forwarded += allowed;
                        budget.remaining -= allowed;
                        if (budget.remaining == 0) {
                            interruptedBytes.set(budget.forwarded);
                            interruption.compareAndSet(budget, null);
                            return true;
                        }
                        return false;
                    }
                }
            }
            destination.getOutputStream().write(buffer, 0, count);
            serverBytes.addAndGet(count);
            return false;
        }

        private void close() {
            if (linkClosed.compareAndSet(false, true)) {
                closeSocket(client);
                closeSocket(upstream);
                disconnected.incrementAndGet();
                links.remove(this);
            }
        }
    }

    private static final class ByteBudget {
        private long remaining;
        private long forwarded;

        private ByteBudget(long remaining) {
            this.remaining = remaining;
        }
    }
}
