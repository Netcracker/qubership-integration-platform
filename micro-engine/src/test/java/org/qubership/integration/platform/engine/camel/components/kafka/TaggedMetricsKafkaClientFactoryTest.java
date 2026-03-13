package org.qubership.integration.platform.engine.camel.components.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import org.apache.camel.component.kafka.KafkaClientFactory;
import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TaggedMetricsKafkaClientFactoryTest {

    @Mock KafkaClientFactory delegate;
    @Mock MeterRegistry meterRegistry;

    private Collection<Tag> tags;
    private TaggedMetricsKafkaClientFactory factory;

    @BeforeEach
    void setUp() {
        tags = List.of(Tag.of("k", "v"));
        factory = new TaggedMetricsKafkaClientFactory(delegate, meterRegistry, tags);
    }

    @Test
    void shouldBindProducerMetricsWhenGetProducer() {
        Properties props = new Properties();

        @SuppressWarnings("rawtypes")
        Producer producer = mock(Producer.class);
        when(delegate.getProducer(props)).thenReturn(producer);

        List<List<Object>> ctorArgs = new ArrayList<>();

        try (MockedConstruction<KafkaClientMetrics> construction =
                     mockConstruction(KafkaClientMetrics.class, (mock, context) -> {
                         ctorArgs.add((List<Object>) context.arguments());
                     })) {

            Object result = factory.getProducer(props);

            assertSame(producer, result);

            verify(delegate).getProducer(props);

            KafkaClientMetrics metrics = construction.constructed().get(0);
            verify(metrics).bindTo(meterRegistry);

            assertEquals(1, ctorArgs.size());
            assertEquals(2, ctorArgs.get(0).size());
            assertSame(producer, ctorArgs.get(0).get(0));
            assertSame(tags, ctorArgs.get(0).get(1));
        }
    }

    @Test
    void shouldBindConsumerMetricsWhenGetConsumer() {
        Properties props = new Properties();

        @SuppressWarnings("rawtypes")
        Consumer consumer = mock(Consumer.class);
        when(delegate.getConsumer(props)).thenReturn(consumer);

        List<List<Object>> ctorArgs = new ArrayList<>();

        try (MockedConstruction<KafkaClientMetrics> construction =
                     mockConstruction(KafkaClientMetrics.class, (mock, context) -> {
                         ctorArgs.add((List<Object>) context.arguments());
                     })) {

            Object result = factory.getConsumer(props);

            assertSame(consumer, result);

            verify(delegate).getConsumer(props);

            KafkaClientMetrics metrics = construction.constructed().get(0);
            verify(metrics).bindTo(meterRegistry);

            assertEquals(1, ctorArgs.size());
            assertEquals(2, ctorArgs.get(0).size());
            assertSame(consumer, ctorArgs.get(0).get(0));
            assertSame(tags, ctorArgs.get(0).get(1));
        }
    }

    @Test
    void shouldDelegateGetBrokers() {
        KafkaConfiguration cfg = mock(KafkaConfiguration.class);
        when(delegate.getBrokers(cfg)).thenReturn("broker:9092");

        String out = factory.getBrokers(cfg);

        assertEquals("broker:9092", out);
        verify(delegate).getBrokers(cfg);
    }
}
