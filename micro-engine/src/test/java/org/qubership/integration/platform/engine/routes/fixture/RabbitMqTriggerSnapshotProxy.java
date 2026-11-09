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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RabbitMqTriggerSnapshotProxy implements AutoCloseable {
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Link> links = ConcurrentHashMap.newKeySet();
    private final ServerSocket listener;
    private volatile InetSocketAddress target;
    private volatile boolean closed;
    private volatile IOException failure;

    RabbitMqTriggerSnapshotProxy(String host, int port) throws IOException {
        target = new InetSocketAddress(host, port);
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        workers.execute(this::accept);
    }

    int port() {
        return listener.getLocalPort();
    }

    void target(String host, int port) {
        target = new InetSocketAddress(host, port);
        List.copyOf(links).forEach(Link::close);
    }

    void disconnect() {
        List.copyOf(links).forEach(Link::close);
    }

    void verify() {
        assertNull(failure, () -> "RabbitMQ forwarding listener failed: " + failure);
    }

    private void accept() {
        while (!closed) {
            Socket client = null;
            Socket upstream = null;
            try {
                client = listener.accept();
                upstream = new Socket();
                upstream.connect(target, 5000);
                Link link = new Link(client, upstream);
                links.add(link);
                workers.execute(() -> link.forward(clientSocket(link), upstreamSocket(link)));
                workers.execute(() -> link.forward(upstreamSocket(link), clientSocket(link)));
            } catch (IOException exception) {
                closeSocket(client);
                closeSocket(upstream);
                if (!closed) {
                    failure = exception;
                }
            }
        }
    }

    private static Socket clientSocket(Link link) {
        return link.client;
    }

    private static Socket upstreamSocket(Link link) {
        return link.upstream;
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Both forwarding tasks close the same sockets.
            }
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        listener.close();
        List.copyOf(links).forEach(Link::close);
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "RabbitMQ forwarding workers did not stop.");
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

        private void forward(Socket source, Socket destination) {
            try {
                source.getInputStream().transferTo(destination.getOutputStream());
            } catch (IOException ignored) {
                // Switching the broker listener closes an established forwarding pair.
            } finally {
                close();
            }
        }

        private void close() {
            if (linkClosed.compareAndSet(false, true)) {
                links.remove(this);
                closeSocket(client);
                closeSocket(upstream);
            }
        }
    }
}
