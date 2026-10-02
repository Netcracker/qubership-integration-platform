package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaSnapshotClientFactoryTest {
    @InjectMocks
    private KafkaSnapshotClientFactory factory;

    @Mock
    private KafkaBGClientFactory delegate;
    @Mock
    private Producer<Object, Object> producer;
    @Mock
    private Runnable closeCallback;

    private static final String TOPIC = "test-topic";

    @Test
    void shouldReadMetadataFromSameProducerWhenCachedTlsErrorsRemain() {
        registerProducer();
        when(producer.partitionsFor(TOPIC))
                .thenThrow(new SslAuthenticationException("Cached TLS failure"))
                .thenThrow(new SslAuthenticationException("Another cached TLS failure"))
                .thenReturn(List.of(new PartitionInfo(TOPIC, 0, null, null, null)));

        factory.awaitMetadata(TOPIC, Duration.ofSeconds(1));

        verify(producer, times(3)).partitionsFor(TOPIC);
        verifyNoMoreInteractions(producer);
        verifyNoInteractions(closeCallback);
    }

    @Test
    void shouldPropagateOtherAuthenticationErrorsWhenReadingMetadata() {
        registerProducer();
        SaslAuthenticationException error = new SaslAuthenticationException("Invalid credentials");
        when(producer.partitionsFor(TOPIC)).thenThrow(error);

        assertSame(error, assertThrows(SaslAuthenticationException.class,
                () -> factory.awaitMetadata(TOPIC, Duration.ofSeconds(1))));

        verify(producer).partitionsFor(TOPIC);
        verifyNoMoreInteractions(producer);
    }

    @Test
    void shouldFailWhenTlsDoesNotRecoverBeforeDeadline() {
        registerProducer();
        SslAuthenticationException error = new SslAuthenticationException("TLS failure");
        when(producer.partitionsFor(TOPIC)).thenThrow(error);

        AssertionError failure = assertThrows(AssertionError.class,
                () -> factory.awaitMetadata(TOPIC, Duration.ZERO));

        assertSame(error, failure.getCause());
        verify(producer).partitionsFor(TOPIC);
        verifyNoMoreInteractions(producer);
    }

    @Test
    void shouldStopReadingMetadataWhenProducerCloseCallbackRuns() {
        Pair<Producer, Runnable> client = registerProducer();

        client.getRight().run();
        factory.awaitMetadata(TOPIC, Duration.ZERO);

        verify(closeCallback).run();
        verifyNoInteractions(producer);
    }

    private Pair<Producer, Runnable> registerProducer() {
        Properties properties = new Properties();
        when(delegate.getProducerWithCloseCallback(properties)).thenReturn(Pair.of(producer, closeCallback));
        Pair<Producer, Runnable> client = factory.getProducerWithCloseCallback(properties);
        assertSame(producer, client.getLeft());
        return client;
    }
}
