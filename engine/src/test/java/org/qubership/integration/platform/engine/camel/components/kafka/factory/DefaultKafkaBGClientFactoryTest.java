package org.qubership.integration.platform.engine.camel.components.kafka.factory;

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import com.netcracker.cloud.maas.bluegreen.kafka.ConsumerConsistencyMode;
import com.netcracker.cloud.maas.bluegreen.kafka.OffsetSetupStrategy;
import com.netcracker.cloud.maas.bluegreen.kafka.impl.BGKafkaConsumerConfig;
import org.apache.camel.component.kafka.KafkaClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtended;
import org.qubership.integration.platform.engine.camel.components.kafka.cloudcore.BGKafkaConsumerExtendedImpl;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockConstruction;

@ExtendWith(MockitoExtension.class)
class DefaultKafkaBGClientFactoryTest {

    @Mock
    private KafkaClientFactory delegate;
    @Mock
    private BlueGreenStatePublisher statePublisher;

    @ParameterizedTest
    @ValueSource(strings = {"earliest", "EARLIEST", "Earliest"})
    void shouldPreserveNativeEarliestAndConfigureBothBlueGreenStrategies(String resetPolicy) {
        Properties properties = new Properties();
        properties.setProperty(AUTO_OFFSET_RESET_CONFIG, resetPolicy);

        BGKafkaConsumerConfig configuration = createConsumerConfiguration(properties);

        assertEquals("earliest", properties.getProperty(AUTO_OFFSET_RESET_CONFIG));
        assertEquals("earliest", configuration.getProperties().get(AUTO_OFFSET_RESET_CONFIG));
        assertEquals(OffsetSetupStrategy.EARLIEST, configuration.getActiveOffsetSetupStrategy());
        assertEquals(OffsetSetupStrategy.EARLIEST, configuration.getCandidateOffsetSetupStrategy());
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "none"})
    void shouldPreserveOtherNativeResetPolicies(String resetPolicy) {
        Properties properties = new Properties();
        properties.setProperty(AUTO_OFFSET_RESET_CONFIG, resetPolicy);

        BGKafkaConsumerConfig configuration = createConsumerConfiguration(properties);

        assertEquals(resetPolicy, configuration.getProperties().get(AUTO_OFFSET_RESET_CONFIG));
        assertEquals(OffsetSetupStrategy.LATEST, configuration.getActiveOffsetSetupStrategy());
        assertEquals(OffsetSetupStrategy.LATEST, configuration.getCandidateOffsetSetupStrategy());
    }

    @Test
    void shouldKeepNativeResetUnspecifiedWhenNoPolicyIsConfigured() {
        BGKafkaConsumerConfig configuration = createConsumerConfiguration(new Properties());

        assertFalse(configuration.getProperties().containsKey(AUTO_OFFSET_RESET_CONFIG));
        assertEquals(OffsetSetupStrategy.LATEST, configuration.getActiveOffsetSetupStrategy());
        assertEquals(OffsetSetupStrategy.LATEST, configuration.getCandidateOffsetSetupStrategy());
    }

    private BGKafkaConsumerConfig createConsumerConfiguration(Properties properties) {
        properties.setProperty(GROUP_ID_CONFIG, "group-a");
        AtomicReference<BGKafkaConsumerConfig> configuration = new AtomicReference<>();
        try (MockedConstruction<BGKafkaConsumerExtendedImpl> consumers = mockConstruction(
                BGKafkaConsumerExtendedImpl.class,
                (consumer, context) -> configuration.set((BGKafkaConsumerConfig) context.arguments().get(0)))) {
            DefaultKafkaBGClientFactory factory = new DefaultKafkaBGClientFactory(delegate, statePublisher);

            BGKafkaConsumerExtended<?, ?> consumer = factory.getConsumer(
                    properties, ConsumerConsistencyMode.EVENTUAL, List.of("topic-a"));

            assertSame(consumers.constructed().get(0), consumer);
        }
        return configuration.get();
    }
}
