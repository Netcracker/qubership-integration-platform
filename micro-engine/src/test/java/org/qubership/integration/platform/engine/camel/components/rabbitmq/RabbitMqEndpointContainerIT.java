package org.qubership.integration.platform.engine.camel.components.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.NoOpMetricsCollector;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.apache.camel.component.springrabbit.SpringRabbitMQProducer;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RabbitMqEndpointContainerIT {
    private static final int AMQP_PORT = 5672;
    private static final int TLS_PORT = 5671;
    private static final String USERNAME = "snapshot";
    private static final String PASSWORD = "snapshot";
    private static final String ROUTING_KEY = "orders";
    private static final String CERTIFICATES = "snapshot-fixtures/kafka/";
    private static final GenericContainer<?> BROKER = new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
            .withExposedPorts(AMQP_PORT, TLS_PORT)
            .withEnv("RABBITMQ_DEFAULT_USER", USERNAME)
            .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withCopyFileToContainer(MountableFile.forClasspathResource(CERTIFICATES + "ca-cert.pem", 0444),
                    "/etc/rabbitmq/tls/ca-cert.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource(CERTIFICATES + "valid-cert.pem", 0444),
                    "/etc/rabbitmq/tls/server-cert.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource(CERTIFICATES + "valid-key.pem", 0444),
                    "/etc/rabbitmq/tls/server-key.pem")
            .withCopyToContainer(Transferable.of("""
                    listeners.tcp.default = 5672
                    listeners.ssl.default = 5671
                    ssl_options.cacertfile = /etc/rabbitmq/tls/ca-cert.pem
                    ssl_options.certfile = /etc/rabbitmq/tls/server-cert.pem
                    ssl_options.keyfile = /etc/rabbitmq/tls/server-key.pem
                    ssl_options.verify = verify_none
                    ssl_options.fail_if_no_peer_cert = false
                    """, 0444), "/etc/rabbitmq/rabbitmq.conf")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
            .withStartupTimeout(Duration.ofSeconds(90));

    private SpringRabbitMQProducer producer;
    private DefaultCamelContext context;
    private Connection adminConnection;
    private Channel adminChannel;
    private String exchangeName;

    @BeforeAll
    static void startBroker() {
        BROKER.start();
    }

    @AfterAll
    static void stopBroker() {
        BROKER.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(BROKER.getHost());
        connectionFactory.setPort(BROKER.getMappedPort(AMQP_PORT));
        connectionFactory.setUsername(USERNAME);
        connectionFactory.setPassword(PASSWORD);
        connectionFactory.setAutomaticRecoveryEnabled(false);
        adminConnection = connectionFactory.newConnection();
        adminChannel = adminConnection.createChannel();
        exchangeName = "endpoint-" + UUID.randomUUID();
        adminChannel.exchangeDeclare(exchangeName, "direct", false, true, null);

        context = new DefaultCamelContext();
        context.getRegistry().bind("rabbitMetrics", new NoOpMetricsCollector());
        context.addComponent("rabbitmq-custom", new SpringRabbitMQCustomComponent());
        context.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (producer != null) {
                producer.stop();
            }
        } finally {
            try {
                if (context != null) {
                    context.close();
                }
            } finally {
                if (adminConnection != null) {
                    adminConnection.close();
                }
            }
        }
    }

    @Test
    void shouldDeliverMessageWhenTlsCertificateIsTrusted() throws Exception {
        String queue = declareQueue(Map.of());
        createSender(TLS_PORT, trustManager("ca-cert.pem"));

        Exchange exchange = publish("trusted TLS", ROUTING_KEY);

        assertNull(exchange.getException());
        assertDelivery(queue, "trusted TLS");
    }

    @Test
    void shouldRejectMessageWhenTlsCertificateIsUntrusted() throws Exception {
        String queue = declareQueue(Map.of());
        createSender(TLS_PORT, trustManager("untrusted-ca-cert.pem"));

        Exchange exchange = publish("untrusted TLS", ROUTING_KEY);

        Exception failure = exchange.getException();
        assertNotNull(failure);
        assertTrue(hasCause(failure, SSLHandshakeException.class), failure::toString);
        assertNull(adminChannel.basicGet(queue, true));
    }

    @Test
    void shouldCompletePublishWhenBrokerAcknowledgesMessage() throws Exception {
        String queue = declareQueue(Map.of());
        createSender(AMQP_PORT, null);

        Exchange exchange = publish("confirmed order", ROUTING_KEY);

        assertNull(exchange.getException());
        assertDelivery(queue, "confirmed order");
    }

    @Test
    void shouldFailOnBrokerNackAndDeliverNextMessageWhenQueueHasSpace() throws Exception {
        String queue = declareQueue(Map.of("x-max-length", 1, "x-overflow", "reject-publish"));
        createSender(AMQP_PORT, null);
        assertNull(publish("first order", ROUTING_KEY).getException());

        Exchange rejected = publish("overflow order", ROUTING_KEY);

        // Camel maps waitForConfirms(false) to TimeoutException; a timed-out wait is wrapped by Spring.
        assertInstanceOf(TimeoutException.class, rejected.getException());
        assertDelivery(queue, "first order");
        assertNull(adminChannel.basicGet(queue, true));

        assertNull(publish("recovered order", ROUTING_KEY).getException());
        assertDelivery(queue, "recovered order");
    }

    @Test
    void shouldReturnUnroutableMandatoryMessageAndDeliverNextRoutedMessage() throws Exception {
        String queue = declareQueue(Map.of());
        CachingConnectionFactory connectionFactory = createSender(AMQP_PORT, null);
        connectionFactory.setPublisherReturns(true);
        CompletableFuture<ReturnedMessage> returned = new CompletableFuture<>();
        // Mandatory publishing is configured through the template, outside the sender element's YAML options.
        producer.getInOnlyTemplate().setMandatory(true);
        producer.getInOnlyTemplate().setReturnsCallback(returned::complete);

        Exchange exchange = publish("unroutable order", "missing.route");

        // A return reports routing failure even though the broker acknowledges the publish.
        assertNull(exchange.getException());
        ReturnedMessage message = returned.get(10, TimeUnit.SECONDS);
        assertEquals(312, message.getReplyCode());
        assertEquals("NO_ROUTE", message.getReplyText());
        assertEquals(exchangeName, message.getExchange());
        assertEquals("missing.route", message.getRoutingKey());
        assertArrayEquals("unroutable order".getBytes(StandardCharsets.UTF_8), message.getMessage().getBody());
        assertNull(adminChannel.basicGet(queue, true));

        assertNull(publish("routed order", ROUTING_KEY).getException());
        assertDelivery(queue, "routed order");
    }

    private String declareQueue(Map<String, Object> arguments) throws Exception {
        String queue = adminChannel.queueDeclare("", false, true, true, arguments).getQueue();
        adminChannel.queueBind(queue, exchangeName, ROUTING_KEY);
        return queue;
    }

    private CachingConnectionFactory createSender(int port, TrustManager trustManager) throws Exception {
        String uri = "rabbitmq-custom:" + exchangeName
                + "?addresses=" + BROKER.getHost() + ':' + BROKER.getMappedPort(port)
                + "&username=" + USERNAME + "&password=" + PASSWORD
                + "&routingKey=" + ROUTING_KEY + "&metricsCollector=#rabbitMetrics"
                + "&autoDeclare=false&autoDeclareProducer=false&testConnectionOnStartup=false"
                + "&confirm=enabled&confirmTimeout=10000&connectionTimeout=10000";
        if (trustManager != null) {
            context.getRegistry().bind("rabbitTrust", trustManager);
            uri += "&sslProtocol=TLS&trustManager=#rabbitTrust";
        }
        SpringRabbitMQCustomEndpoint endpoint = context.getEndpoint(uri, SpringRabbitMQCustomEndpoint.class);
        CachingConnectionFactory connectionFactory = (CachingConnectionFactory) endpoint.getConnectionFactory();
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        producer = (SpringRabbitMQProducer) endpoint.createProducer();
        producer.start();
        return connectionFactory;
    }

    private Exchange publish(String body, String routingKey) throws Exception {
        Exchange exchange = producer.getEndpoint().createExchange(ExchangePattern.InOnly);
        exchange.getMessage().setBody(body);
        exchange.getMessage().setHeader(SpringRabbitMQConstants.ROUTING_OVERRIDE_KEY, routingKey);
        producer.process(exchange);
        return exchange;
    }

    private void assertDelivery(String queue, String expectedBody) throws Exception {
        GetResponse delivery = adminChannel.basicGet(queue, true);
        assertNotNull(delivery);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), delivery.getBody());
        assertEquals(exchangeName, delivery.getEnvelope().getExchange());
        assertEquals(ROUTING_KEY, delivery.getEnvelope().getRoutingKey());
    }

    private static TrustManager trustManager(String certificate) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        try (InputStream input = RabbitMqEndpointContainerIT.class.getClassLoader()
                .getResourceAsStream(CERTIFICATES + certificate)) {
            trustStore.setCertificateEntry("rabbit-ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return factory.getTrustManagers()[0];
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
