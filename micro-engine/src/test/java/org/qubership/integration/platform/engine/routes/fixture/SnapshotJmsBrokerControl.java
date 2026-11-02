package org.qubership.integration.platform.engine.routes.fixture;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.rnorth.ducttape.ratelimits.RateLimiter;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class SnapshotJmsBrokerControl implements AutoCloseable {
    private static final String ARTEMIS_IMAGE = "apache/artemis:2.55.0-alpine";
    private static final String USERNAME = "snapshot";
    private static final String PASSWORD = "snapshot";
    private static final String DENIED_USERNAME = "snapshot-denied";
    private static final int AMQP_PORT = 61616;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final long REQUEST_TIMEOUT_MILLIS = 5000;

    private final Map<String, Integer> deliveryLimits = new LinkedHashMap<>();
    private GenericContainer<?> container;
    private JmsConnectionFactory producerFactory;
    private JmsConnectionFactory observerFactory;
    private SnapshotJmsBrokerManagement management;
    private boolean stopped;
    private boolean deniedUserCreated;

    void start() throws IOException {
        int brokerPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            brokerPort = socket.getLocalPort();
        }
        container = new GenericContainer<>(DockerImageName.parse(ARTEMIS_IMAGE))
                .withExposedPorts(AMQP_PORT)
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(brokerPort), ExposedPort.tcp(AMQP_PORT))))
                .withEnv("ARTEMIS_USER", USERNAME)
                .withEnv("ARTEMIS_PASSWORD", PASSWORD)
                .withEnv("ANONYMOUS_LOGIN", Boolean.FALSE.toString())
                .withEnv("EXTRA_ARGS", "--http-host 0.0.0.0 --relax-jolokia --no-autotune --no-web")
                .withStartupTimeout(STARTUP_TIMEOUT);
        container.start();
        String brokerUrl = "amqp://" + container.getHost() + ':' + container.getMappedPort(AMQP_PORT);
        producerFactory = createConnectionFactory(brokerUrl);
        observerFactory = createConnectionFactory(brokerUrl);
        awaitBroker();
    }

    ConnectionFactory connectionFactory() {
        return producerFactory;
    }

    Connection createVerificationConnection() throws JMSException {
        return observerFactory.createConnection();
    }

    void setMaxDeliveryAttempts(String destination, int attempts) throws Exception {
        management().setMaxDeliveryAttempts(destination, attempts);
        // Qpid recovers CLIENT_ACKNOWLEDGE deliveries locally before the broker sees a rejection.
        deliveryLimits.put(destination, attempts);
        producerFactory.setRedeliveryPolicy(new SnapshotJmsRedeliveryPolicy(deliveryLimits));
    }

    int topicConsumerCount(String destination) {
        try {
            return management().topicConsumerCount(destination);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot observe Artemis subscriptions for '" + destination + "'.", exception);
        }
    }

    private SnapshotJmsBrokerManagement management() throws Exception {
        if (management == null) {
            management = new SnapshotJmsBrokerManagement(createVerificationConnection());
        }
        return management;
    }

    static boolean disconnectsObservers(Map<String, Object> properties) {
        return "CONNECTION_REFUSED".equals(properties.get("brokerFault"));
    }

    void beforeInvocation(Map<String, Object> properties) throws Exception {
        producerFactory.setUsername(USERNAME);
        producerFactory.setPassword(PASSWORD);
        Object fault = properties.get("brokerFault");
        if (fault == null) {
            return;
        }
        switch (fault.toString()) {
            case "AUTHENTICATION" -> producerFactory.setPassword("wrong-password");
            case "PERMISSION" -> {
                createDeniedUser();
                producerFactory.setUsername(DENIED_USERNAME);
            }
            case "CONNECTION_REFUSED" -> {
                closeManagement();
                container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(10).exec();
                stopped = true;
            }
            default -> throw new IllegalArgumentException("Unsupported JMS brokerFault '" + fault + "'.");
        }
    }

    boolean restoreAfterInvocation() {
        if (!stopped) {
            return false;
        }
        container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
        awaitBroker();
        stopped = false;
        return true;
    }

    private void createDeniedUser() throws Exception {
        if (deniedUserCreated) {
            return;
        }
        management().addUser(DENIED_USERNAME, PASSWORD, "snapshot-denied");
        deniedUserCreated = true;
    }

    private void awaitBroker() {
        RateLimiter limiter = RateLimiterBuilder.newBuilder().withRate(2, TimeUnit.SECONDS)
                .withConstantThroughput().build();
        Unreliables.retryUntilSuccess((int) STARTUP_TIMEOUT.toSeconds(), TimeUnit.SECONDS,
                () -> limiter.getWhenReady(() -> {
                    try (Connection connection = observerFactory.createConnection()) {
                        connection.start();
                    }
                    return null;
                }));
    }

    private static JmsConnectionFactory createConnectionFactory(String brokerUrl) {
        JmsConnectionFactory factory = new JmsConnectionFactory(USERNAME, PASSWORD, brokerUrl);
        factory.setConnectTimeout(REQUEST_TIMEOUT_MILLIS);
        factory.setRequestTimeout(REQUEST_TIMEOUT_MILLIS);
        factory.setSendTimeout(REQUEST_TIMEOUT_MILLIS);
        return factory;
    }

    private void closeManagement() {
        if (management != null) {
            try {
                management.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot close the Artemis management connection.", exception);
            } finally {
                management = null;
            }
        }
    }

    @Override
    public void close() {
        try {
            closeManagement();
        } finally {
            if (container != null) {
                container.stop();
                container = null;
            }
        }
    }
}
