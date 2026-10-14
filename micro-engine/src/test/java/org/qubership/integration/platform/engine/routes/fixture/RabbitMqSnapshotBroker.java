package org.qubership.integration.platform.engine.routes.fixture;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RabbitMqSnapshotBroker {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final String INSPECTION_USERNAME = "snapshot";

    private final GenericContainer<?> container;
    private final Set<String> users = new HashSet<>(Set.of(INSPECTION_USERNAME));
    private final Set<String> vhosts = new HashSet<>(Set.of("/"));
    private final Set<CachingConnectionFactory> observedFactories = new HashSet<>();
    private final List<Integer> closeCodes = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Integer> shutdowns = new LinkedBlockingQueue<>();
    private boolean running = true;

    RabbitMqSnapshotBroker(GenericContainer<?> container) {
        this.container = container;
    }

    void ensureAccount(String username, String password, String vhost) throws Exception {
        if (users.add(username)) {
            execute("add_user", username, password);
        }
        if (vhosts.add(vhost)) {
            execute("add_vhost", vhost);
        }
        grantPermissions(INSPECTION_USERNAME, vhost);
        if (!INSPECTION_USERNAME.equals(username)) {
            grantPermissions(username, vhost);
        }
    }

    void observe(CachingConnectionFactory factory) {
        if (observedFactories.add(factory)) {
            factory.getRabbitConnectionFactory().setHandshakeTimeout(2000);
            factory.getRabbitConnectionFactory().setShutdownTimeout(2000);
            factory.getRabbitConnectionFactory().setChannelRpcTimeout(2000);
            factory.addChannelListener((channel, transactional) -> channel.addShutdownListener(this::recordShutdown));
        }
    }

    void beforeInvocation(Map<String, Object> properties, Collection<CachingConnectionFactory> factories) throws Exception {
        closeCodes.clear();
        shutdowns.clear();
        Object configured = properties.get("brokerAction");
        if (configured == null) {
            return;
        }
        if (!(configured instanceof Map<?, ?> action)) {
            throw new IllegalArgumentException("RabbitMQ brokerAction must be a map.");
        }
        switch (field(action, "action")) {
            case "change-password", "restore-password" -> {
                execute("change_password", field(action, "username"), field(action, "password"));
                resetConnections(factories);
            }
            case "delete-user" -> {
                String username = field(action, "username");
                execute("delete_user", username);
                users.remove(username);
                resetConnections(factories);
            }
            case "restore-user" -> {
                ensureAccount(field(action, "username"), field(action, "password"), field(action, "vhost"));
                resetConnections(factories);
            }
            case "deny-vhost" -> {
                execute("clear_permissions", "-p", field(action, "vhost"), field(action, "username"));
                resetConnections(factories);
            }
            case "restore-vhost", "restore-write" -> {
                grantPermissions(field(action, "username"), field(action, "vhost"));
                resetConnections(factories);
            }
            case "deny-write" -> {
                execute("set_permissions", "-p", field(action, "vhost"), field(action, "username"), ".*", "^$", ".*");
                resetConnections(factories);
            }
            case "close-connections" -> {
                closeConnections(field(action, "username"));
                awaitCloseCode(320);
            }
            case "stop" -> {
                execute("stop_app");
                running = false;
                resetConnections(factories);
            }
            case "start" -> {
                execute("start_app");
                running = true;
            }
            default -> throw new IllegalArgumentException("Unsupported RabbitMQ broker action '" + action.get("action") + "'.");
        }
    }

    void verifyInvocation(Map<String, Object> properties) {
        Object configured = properties.get("expectedChannelCloseCode");
        if (configured instanceof Number code) {
            awaitCloseCode(code.intValue());
        } else if (configured != null) {
            throw new IllegalArgumentException("RabbitMQ expectedChannelCloseCode must be a number.");
        }
    }

    boolean isRunning() {
        return running;
    }

    private void closeConnections(String username) throws Exception {
        execute("close_all_user_connections", username, "Snapshot connection reset");
    }

    private void grantPermissions(String username, String vhost) throws Exception {
        execute("set_permissions", "-p", vhost, username, ".*", ".*", ".*");
    }

    private void recordShutdown(ShutdownSignalException signal) {
        if (!signal.isInitiatedByApplication()) {
            if (signal.getReason() instanceof AMQP.Channel.Close close) {
                recordCloseCode(close.getReplyCode());
            } else if (signal.getReason() instanceof AMQP.Connection.Close close) {
                recordCloseCode(close.getReplyCode());
            }
        }
    }

    private void recordCloseCode(int code) {
        closeCodes.add(code);
        shutdowns.add(code);
    }

    private void awaitCloseCode(int code) {
        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        try {
            while (!closeCodes.contains(code) && System.nanoTime() < deadline) {
                shutdowns.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for RabbitMQ channel shutdown.", exception);
        }
        assertTrue(closeCodes.contains(code),
                () -> "RabbitMQ did not close the producer channel with code " + code + "; received " + closeCodes + '.');
    }

    private String execute(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("timeout", "30", "rabbitmqctl", "--timeout", "20", "--quiet"));
        command.addAll(List.of(arguments));
        Container.ExecResult result = container.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("RabbitMQ command '" + arguments[0] + "' failed: " + result.getStderr());
        }
        return result.getStdout();
    }

    private static void resetConnections(Collection<CachingConnectionFactory> factories) {
        factories.forEach(CachingConnectionFactory::resetConnection);
    }

    private static String field(Map<?, ?> action, String name) {
        Object value = action.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("RabbitMQ brokerAction requires a nonblank '" + name + "'.");
        }
        return text;
    }
}
