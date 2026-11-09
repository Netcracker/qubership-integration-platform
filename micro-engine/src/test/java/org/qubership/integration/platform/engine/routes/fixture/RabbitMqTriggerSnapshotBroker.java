package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RabbitMqTriggerSnapshotBroker implements SnapshotWorkerBrokerPool.Resource {
    static final String INSPECTOR = "snapshot";
    private static final int AMQP_PORT = 5672;
    private static final int TLS_PORT = 5671;
    private final boolean tls;
    private final Set<String> users = new LinkedHashSet<>(Set.of(INSPECTOR));
    private final Set<String> vhosts = new LinkedHashSet<>(Set.of("/"));
    private GenericContainer<?> container;
    private RabbitMqTriggerSnapshotProxy tlsProxy;
    private Set<String> initialExchanges;

    RabbitMqTriggerSnapshotBroker(boolean tls) {
        this.tls = tls;
    }

    void start() throws Exception {
        container = new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
                .withExposedPorts(AMQP_PORT)
                .withEnv("RABBITMQ_DEFAULT_USER", INSPECTOR)
                .withEnv("RABBITMQ_DEFAULT_PASS", INSPECTOR)
                .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                .withStartupTimeout(Duration.ofSeconds(90));
        if (tls) {
            container.withExposedPorts(AMQP_PORT, TLS_PORT)
                    .withCopyFileToContainer(MountableFile.forClasspathResource("snapshot-fixtures/kafka/ca-cert.pem", 0444),
                            "/etc/rabbitmq/tls/ca-cert.pem")
                    .withCopyFileToContainer(MountableFile.forClasspathResource("snapshot-fixtures/kafka/valid-cert.pem", 0444),
                            "/etc/rabbitmq/tls/server-cert.pem")
                    .withCopyFileToContainer(MountableFile.forClasspathResource("snapshot-fixtures/kafka/valid-key.pem", 0444),
                            "/etc/rabbitmq/tls/server-key.pem")
                    .withCopyToContainer(Transferable.of("""
                            listeners.tcp.default = 5672
                            listeners.ssl.default = 5671
                            ssl_options.cacertfile = /etc/rabbitmq/tls/ca-cert.pem
                            ssl_options.certfile = /etc/rabbitmq/tls/server-cert.pem
                            ssl_options.keyfile = /etc/rabbitmq/tls/server-key.pem
                            ssl_options.verify = verify_none
                            ssl_options.fail_if_no_peer_cert = false
                            """, 0444), "/etc/rabbitmq/rabbitmq.conf");
        }
        container.start();
        if (tls) {
            tlsProxy = new RabbitMqTriggerSnapshotProxy(container.getHost(), container.getMappedPort(TLS_PORT));
        }
    }

    String consumerHost() {
        return tls ? "127.0.0.1" : container.getHost();
    }

    int consumerPort() {
        return tls ? tlsProxy.port() : container.getMappedPort(AMQP_PORT);
    }

    void tlsUnavailable(boolean unavailable) {
        assertTrue(tls, "A TLS listener fault requires a TLS broker fixture.");
        tlsProxy.target(container.getHost(), container.getMappedPort(unavailable ? AMQP_PORT : TLS_PORT));
    }

    ConnectionFactory publisherFactory(String vhost) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(container.getHost());
        factory.setPort(container.getMappedPort(AMQP_PORT));
        factory.setUsername(INSPECTOR);
        factory.setPassword(INSPECTOR);
        factory.setVirtualHost(vhost);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setConnectionTimeout(5000);
        factory.setHandshakeTimeout(5000);
        factory.setChannelRpcTimeout(5000);
        factory.setShutdownTimeout(2000);
        return factory;
    }

    void ensureAccount(String username, String password, String vhost) throws Exception {
        if (users.add(username)) {
            execute("add_user", username, password);
        }
        if (vhosts.add(vhost)) {
            execute("add_vhost", vhost);
        }
        permissions(INSPECTOR, vhost, ".*", ".*", ".*");
        if (!INSPECTOR.equals(username)) {
            permissions(username, vhost, ".*", ".*", ".*");
        }
    }

    void permissions(String username, String vhost, String configure, String write, String read) throws Exception {
        execute("set_permissions", "-p", vhost, username, configure, write, read);
    }

    void password(String username, String password) throws Exception {
        assertNotEquals(INSPECTOR, username, "Fault tests require an application user distinct from the inspector.");
        execute("change_password", username, password);
    }

    void deleteVhost(String vhost) throws Exception {
        assertNotEquals("/", vhost, "Vhost removal requires a dedicated application vhost.");
        execute("delete_vhost", vhost);
        vhosts.remove(vhost);
    }

    void closeConnections(String username) throws Exception {
        execute("close_all_user_connections", username, "Snapshot trigger connection loss");
    }

    void restart() throws Exception {
        execute("stop_app");
        execute("start_app");
    }

    void provision(String vhost, String exchange, String exchangeType, List<String> queues, String routingKey,
                   Map<String, String> endpoint, Map<String, Object> controls) throws Exception {
        String topology = String.valueOf(controls.getOrDefault("topology", "full"));
        if ("missing-queue".equals(topology)) {
            return;
        }
        try (Connection connection = publisherFactory(vhost).newConnection(); Channel channel = connection.createChannel()) {
            if ("queue-only".equals(topology)) {
                for (String queue : queues) {
                    channel.queueDeclare(queue, true, false, false, Map.of());
                }
                return;
            }
            channel.exchangeDeclare(exchange, exchangeType, true);
            Map<String, Object> queueArguments = new LinkedHashMap<>();
            String deadExchange = endpoint.get("deadLetterExchange");
            String deadQueue = endpoint.get("deadLetterQueue");
            String deadKey = endpoint.getOrDefault("deadLetterRoutingKey", "");
            if (Boolean.TRUE.equals(controls.getOrDefault("deadLetterPolicy", true)) && deadExchange != null && deadQueue != null) {
                channel.exchangeDeclare(deadExchange, endpoint.getOrDefault("deadLetterExchangeType", "direct"), true);
                channel.queueDeclare(deadQueue, true, false, false, Map.of());
                channel.queueBind(deadQueue, deadExchange, deadKey);
                queueArguments.put("x-dead-letter-exchange", deadExchange);
                queueArguments.put("x-dead-letter-routing-key", deadKey);
            }
            String bindingKey = String.valueOf(controls.getOrDefault("bindingKey", routingKey));
            Map<String, Object> bindingArguments = map(controls.get("bindingArguments"));
            for (String queue : queues) {
                channel.queueDeclare(queue, true, false, false, queueArguments);
                channel.queueBind(queue, exchange, bindingKey, bindingArguments);
                if (controls.get("bindings") instanceof List<?> bindings) {
                    for (Object configured : bindings) {
                        Map<String, Object> binding = map(configured);
                        channel.queueBind(queue, exchange, String.valueOf(binding.getOrDefault("routingKey", "")),
                                map(binding.get("arguments")));
                    }
                }
            }
            if (Boolean.TRUE.equals(controls.get("observeFanout"))) {
                String observer = observerQueue(queues.getFirst());
                channel.queueDeclare(observer, true, false, false, Map.of());
                channel.queueBind(observer, exchange, "");
            }
        }
    }

    static String observerQueue(String inputQueue) {
        return inputQueue + ".observation";
    }

    void deleteQueue(String vhost, String queue) throws Exception {
        try (Connection connection = publisherFactory(vhost).newConnection(); Channel channel = connection.createChannel()) {
            channel.queueDelete(queue);
        }
    }

    int consumerCount(String vhost, List<String> queues) throws Exception {
        int count = 0;
        try (Connection connection = publisherFactory(vhost).newConnection()) {
            for (String queue : queues) {
                try (Channel channel = connection.createChannel()) {
                    count += channel.queueDeclarePassive(queue).getConsumerCount();
                }
            }
        }
        return count;
    }

    int readyCount(String vhost, List<String> queues) throws Exception {
        int count = 0;
        try (Connection connection = publisherFactory(vhost).newConnection()) {
            for (String queue : queues) {
                try (Channel channel = connection.createChannel()) {
                    count += channel.queueDeclarePassive(queue).getMessageCount();
                }
            }
        }
        return count;
    }

    List<Map<String, Object>> queueStates(String vhost) throws Exception {
        String response = execute("list_queues", "-p", vhost, "name", "messages_ready", "messages_unacknowledged", "consumers",
                "--formatter", "json");
        int start = response.indexOf('[');
        if (start < 0) {
            throw new IllegalStateException("RabbitMQ queue inspection returned no JSON array: " + response);
        }
        return ObjectMappers.getObjectMapper().readValue(response.substring(start), new TypeReference<>() { });
    }

    int unacknowledged(String vhost, List<String> queues) throws Exception {
        return queueStates(vhost).stream().filter(state -> queues.contains(state.get("name")))
                .mapToInt(state -> ((Number) state.get("messages_unacknowledged")).intValue()).sum();
    }

    List<GetResponse> drain(String vhost, String queue, int expected) throws Exception {
        if (queue == null) {
            assertEquals(0, expected, "A dead-letter assertion requires a configured DLQ.");
            return List.of();
        }
        List<GetResponse> records = new ArrayList<>();
        try (Connection connection = publisherFactory(vhost).newConnection(); Channel channel = connection.createChannel()) {
            GetResponse delivery;
            while ((delivery = channel.basicGet(queue, true)) != null) {
                records.add(delivery);
            }
        } catch (IOException exception) {
            if (expected != 0 || !hasReplyCode(exception, 404)) {
                throw exception;
            }
        }
        return List.copyOf(records);
    }

    boolean queueExists(String vhost, String queue) throws Exception {
        try (Connection connection = publisherFactory(vhost).newConnection(); Channel channel = connection.createChannel()) {
            channel.queueDeclarePassive(queue);
            return true;
        } catch (IOException exception) {
            if (hasReplyCode(exception, 404)) {
                return false;
            }
            throw exception;
        }
    }

    boolean exchangeExists(String vhost, String exchange) throws Exception {
        try (Connection connection = publisherFactory(vhost).newConnection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclarePassive(exchange);
            return true;
        } catch (IOException exception) {
            if (hasReplyCode(exception, 404)) {
                return false;
            }
            throw exception;
        }
    }

    private static boolean hasReplyCode(Throwable failure, int code) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof com.rabbitmq.client.ShutdownSignalException signal
                    && signal.getReason() instanceof com.rabbitmq.client.AMQP.Channel.Close close
                    && close.getReplyCode() == code) {
                return true;
            }
        }
        return false;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> values ? (Map<String, Object>) values : Map.of();
    }

    void enableReuse() throws Exception {
        initialExchanges = names(inspect("list_exchanges", "-p", "/", "name"));
    }

    @Override
    public void resetAfterScenario() throws Exception {
        Unreliables.retryUntilTrue(20, TimeUnit.SECONDS, () -> inspect("list_connections", "name").isEmpty());
        assertEquals(Set.of(INSPECTOR), users, "Reusable RabbitMQ scenarios retain the inspector account.");
        assertEquals(Set.of("/"), vhosts, "Reusable RabbitMQ scenarios use the default vhost.");
        Map<String, Object> before = definitions();
        try (Connection connection = publisherFactory("/").newConnection(); Channel channel = connection.createChannel()) {
            for (String queue : names(resources(before, "queues"))) {
                channel.queueDelete(queue);
            }
            for (String exchange : names(resources(before, "exchanges"))) {
                if (!initialExchanges.contains(exchange)) {
                    channel.exchangeDelete(exchange);
                }
            }
            for (String exchange : initialExchanges) {
                // AMQP forbids declarations of the implicit default exchange.
                if (!exchange.isEmpty()) {
                    channel.exchangeDeclarePassive(exchange);
                }
            }
        }
        Map<String, Object> after = definitions();
        assertTrue(resources(after, "queues").isEmpty(), "RabbitMQ reset must remove every queue and its messages and consumers.");
        assertTrue(resources(after, "exchanges").isEmpty(), "RabbitMQ reset must remove application exchanges.");
        assertTrue(resources(after, "bindings").isEmpty(), "RabbitMQ reset must remove scenario bindings.");
    }

    private Map<String, Object> definitions() throws Exception {
        return ObjectMappers.getObjectMapper().readValue(execute("export_definitions", "-"), new TypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resources(Map<String, Object> definitions, String kind) {
        return (List<Map<String, Object>>) definitions.get(kind);
    }

    private List<Map<String, Object>> inspect(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(arguments));
        command.addAll(List.of("--formatter", "json"));
        String response = execute(command.toArray(String[]::new));
        return ObjectMappers.getObjectMapper().readValue(response.substring(response.indexOf('[')), new TypeReference<>() { });
    }

    private static Set<String> names(List<Map<String, Object>> resources) {
        return resources.stream().map(resource -> String.valueOf(resource.get("name"))).collect(Collectors.toSet());
    }

    @Override
    public void close() throws Exception {
        try {
            if (tlsProxy != null) {
                tlsProxy.close();
            }
        } finally {
            if (container != null) {
                container.stop();
            }
        }
    }
}
