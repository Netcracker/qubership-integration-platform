package org.qubership.integration.platform.engine.camel.predeploy;

import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.camel.component.kafka.KafkaEndpoint;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomEndpoint;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.configuration.PredeployCheckKafkaConfiguration;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaTopicCheckTest {

    @Mock
    private PredeployCheckKafkaConfiguration configuration;

    @Mock
    private AdminClient adminClient;

    @Mock
    private ListTopicsResult listTopicsResult;

    private MockedStatic<AdminClient> adminClientStatic;

    private KafkaTopicCheck check;

    @BeforeEach
    void setUp() {
        adminClientStatic = mockStatic(AdminClient.class);
        check = new KafkaTopicCheck(configuration);
    }

    @AfterEach
    void tearDown() {
        adminClientStatic.close();
    }

    @Test
    void shouldApplyToKafkaSchemes() {
        when(configuration.isCamelKafkaPredeployCheckEnabled()).thenReturn(true);

        assertTrue(check.applicableTo("kafka-custom"));
        assertTrue(check.applicableTo("kafka"));
        assertFalse(check.applicableTo("rabbitmq-custom"));
    }

    @Test
    void shouldNotApplyWhenDisabled() {
        when(configuration.isCamelKafkaPredeployCheckEnabled()).thenReturn(false);

        assertFalse(check.applicableTo("kafka-custom"));
    }

    @Test
    void shouldPassWhenBrokerListsEveryTopic() {
        brokerLists(KafkaFuture.completedFuture(Set.of("orders", "payments", "other")));

        assertDoesNotThrow(() -> check.check(customEndpoint("kafka:9092", "orders,payments"), true));
    }

    @Test
    void shouldFailNamingTopicsBrokerDoesNotList() {
        brokerLists(KafkaFuture.completedFuture(Set.of("orders")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> check.check(customEndpoint("kafka:9092", "orders,missing"), false));

        assertEquals("Kafka topics (missing) not found on brokers kafka:9092. "
                + "Create the topics or change the element.", failure.getMessage());
    }

    @Test
    void shouldCheckLegacyKafkaEndpoint() {
        brokerLists(KafkaFuture.completedFuture(Set.of()));
        KafkaConfiguration config = new KafkaConfiguration();
        config.setBrokers("kafka:9092");
        config.setTopic("legacy");
        KafkaEndpoint endpoint = mock(KafkaEndpoint.class);
        when(endpoint.getConfiguration()).thenReturn(config);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> check.check(endpoint, true));

        assertTrue(failure.getMessage().startsWith("Kafka topics (legacy) not found"));
    }

    @Test
    void shouldSkipWhenBrokerRefusesToListTopics() {
        brokerLists(KafkaFuture.<Set<String>>completedFuture(null).thenApply(ignored -> {
            throw new TopicAuthorizationException("denied");
        }));

        assertDoesNotThrow(() -> check.check(customEndpoint("kafka:9092", "orders"), true));
    }

    @Test
    void shouldFailWhenBrokerIsUnavailable() {
        brokerLists(KafkaFuture.<Set<String>>completedFuture(null).thenApply(ignored -> {
            throw new TimeoutException("timed out");
        }));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> check.check(customEndpoint("kafka:9092", "orders"), true));

        assertTrue(failure.getMessage().startsWith("Kafka predeploy check failed for brokers kafka:9092"));
    }

    private void brokerLists(KafkaFuture<Set<String>> names) {
        Map<String, Object> adminConfig = Map.of("bootstrap.servers", "kafka:9092");
        when(configuration.createValidationKafkaAdminConfig(eq("kafka:9092"), any(), any(), any())).thenReturn(adminConfig);
        adminClientStatic.when(() -> AdminClient.create(anyMap())).thenReturn(adminClient);
        when(adminClient.listTopics()).thenReturn(listTopicsResult);
        when(listTopicsResult.names()).thenReturn(names);
    }

    private static KafkaCustomEndpoint customEndpoint(String brokers, String topics) {
        KafkaCustomConfiguration config = new KafkaCustomConfiguration();
        config.setBrokers(brokers);
        config.setTopic(topics);
        KafkaCustomEndpoint endpoint = new KafkaCustomEndpoint();
        endpoint.setConfiguration(config);
        return endpoint;
    }
}
