package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import org.apache.camel.Processor;
import org.apache.camel.component.kafka.PollOnError;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.StateRepository;
import org.apache.camel.support.service.ServiceSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomEndpoint;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaBGConsumerTest {

    private static final String TOPIC = "topic-a";

    private DefaultCamelContext camelContext;

    @Mock
    private KafkaCustomEndpoint endpoint;
    @Mock
    private Processor processor;
    @Mock
    private KafkaBGClientFactory kafkaClientFactory;
    @Mock
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
        when(endpoint.getCamelContext()).thenReturn(camelContext);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.close();
    }

    @Test
    void shouldReturnKafkaCustomEndpoint() {
        KafkaBGConsumer consumer = new KafkaBGConsumer(endpoint, processor);

        assertSame(endpoint, consumer.getEndpoint());
    }

    @Test
    void shouldSubmitFetchRecordTaskForEachConfiguredConsumerWhenStarted() throws Exception {
        KafkaCustomConfiguration configuration = baseConfiguration();
        configuration.setTopicIsPattern(true);
        configuration.setGroupId("group-a");
        configuration.setGroupInstanceId("instance-a");
        configuration.setConsumersCount(2);
        configuration.setConsumerConsistencyMode(ConsumerConsistencyMode.GUARANTEE_CONSUMPTION.name());
        configuration.setPollOnError(PollOnError.DISCARD);
        stubEndpoint(configuration);
        stubExecutorTermination();

        KafkaBGConsumer consumer = new KafkaBGConsumer(endpoint, processor);
        List<List<?>> constructorArgs = new ArrayList<>();

        try (MockedConstruction<KafkaBGFetchRecords> construction =
                     mockConstruction(KafkaBGFetchRecords.class,
                             (mock, context) -> constructorArgs.add(context.arguments()))) {
            consumer.doStart();

            assertEquals(2, construction.constructed().size());
            verify(executor, times(2)).submit(any(KafkaBGFetchRecords.class));
            verify(endpoint, times(2)).updateClassProperties(any(Properties.class));
            verify(kafkaClientFactory, times(2)).getBrokers(configuration);

            List<?> firstArgs = constructorArgs.get(0);
            List<?> secondArgs = constructorArgs.get(1);
            assertSame(consumer, firstArgs.get(0));
            assertEquals(TOPIC, firstArgs.get(2));
            assertInstanceOf(Pattern.class, firstArgs.get(3));
            assertEquals("0", firstArgs.get(4));
            assertEquals("1", secondArgs.get(4));
            assertProps(firstArgs.get(5), "group-a", "instance-a");
            assertEquals(ConsumerConsistencyMode.GUARANTEE_CONSUMPTION, firstArgs.get(6));
            assertEquals(PollOnError.DISCARD, firstArgs.get(7));
        } finally {
            consumer.doStop();
        }
    }

    @Test
    void shouldGenerateGroupIdWhenGroupIdMissing() throws Exception {
        KafkaCustomConfiguration configuration = baseConfiguration();
        configuration.setGroupId(null);
        stubEndpoint(configuration);
        stubExecutorTermination();

        KafkaBGConsumer consumer = new KafkaBGConsumer(endpoint, processor);
        List<List<?>> constructorArgs = new ArrayList<>();

        try (MockedConstruction<KafkaBGFetchRecords> construction =
                     mockConstruction(KafkaBGFetchRecords.class,
                             (mock, context) -> constructorArgs.add(context.arguments()))) {
            consumer.doStart();

            assertEquals(1, construction.constructed().size());
            Properties props = (Properties) constructorArgs.get(0).get(5);
            assertNotNull(UUID.fromString((String) props.get(ConsumerConfig.GROUP_ID_CONFIG)));
            assertFalse(props.containsKey(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG));
        } finally {
            consumer.doStop();
        }
    }

    @Test
    void shouldStartAndStopOwnedOffsetRepository() throws Exception {
        TestStateRepository repository = new TestStateRepository();
        KafkaCustomConfiguration configuration = baseConfiguration();
        configuration.setOffsetRepository(repository);
        stubEndpoint(configuration);
        stubExecutorTermination();

        KafkaBGConsumer consumer = new KafkaBGConsumer(endpoint, processor);

        try (MockedConstruction<KafkaBGFetchRecords> construction =
                     mockConstruction(KafkaBGFetchRecords.class)) {
            consumer.doStart();
            assertTrue(repository.isStarted());

            consumer.doStop();

            verify(construction.constructed().get(0)).stop();
        }

        assertTrue(repository.isStopped() || repository.isShutdown());
    }

    @Test
    void shouldThrowWhenConsumerConsistencyModeInvalid() {
        KafkaCustomConfiguration configuration = baseConfiguration();
        configuration.setConsumerConsistencyMode("UNKNOWN");
        stubEndpoint(configuration);

        KafkaBGConsumer consumer = new KafkaBGConsumer(endpoint, processor);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, consumer::doStart);

        assertEquals(
                "Invalid enum value for consumerConsistencyMode property: [UNKNOWN]."
                        + " Allowed: [EVENTUAL, GUARANTEE_CONSUMPTION]",
                exception.getMessage()
        );
        verify(executor, never()).submit(any(Runnable.class));
    }

    private void stubEndpoint(KafkaCustomConfiguration configuration) {
        when(endpoint.getConfiguration()).thenReturn(configuration);
        when(endpoint.getKafkaClientFactory()).thenReturn(kafkaClientFactory);
        when(endpoint.createExecutor()).thenReturn(executor);
        when(kafkaClientFactory.getBrokers(configuration)).thenReturn("broker:9092");
    }

    private void stubExecutorTermination() throws InterruptedException {
        when(executor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(executor.isTerminated()).thenReturn(true);
    }

    private static KafkaCustomConfiguration baseConfiguration() {
        KafkaCustomConfiguration configuration = new KafkaCustomConfiguration();
        configuration.setTopic(TOPIC);
        configuration.setConsumersCount(1);
        configuration.setGroupId("group-a");
        configuration.setConsumerConsistencyMode(ConsumerConsistencyMode.EVENTUAL.name());
        return configuration;
    }

    private static void assertProps(Object propsObject, String groupId, String groupInstanceId) {
        assertInstanceOf(Properties.class, propsObject);
        Properties props = (Properties) propsObject;
        assertEquals("broker:9092", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals(groupId, props.get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals(groupInstanceId, props.get(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG));
    }

    private static final class TestStateRepository extends ServiceSupport
            implements StateRepository<String, String> {

        private final Map<String, String> state = new HashMap<>();

        @Override
        public void setState(String key, String value) {
            state.put(key, value);
        }

        @Override
        public String getState(String key) {
            return state.get(key);
        }
    }
}
