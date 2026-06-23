package org.qubership.integration.platform.engine.camel.components.kafka;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.SynchronousDelegateProducer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.camel.components.kafka.consumer.KafkaBGConsumer;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.producer.KafkaCustomProducer;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.security.auth.callback.Callback;
import javax.security.auth.login.AppConfigurationEntry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaCustomEndpointTest {

    private static final String CALLBACK_HANDLER_CLASS_CONFIG = "sasl.login.callback.handler.class";

    private DefaultCamelContext camelContext;
    private KafkaCustomComponent component;
    private KafkaCustomEndpoint endpoint;

    @Mock
    private KafkaCustomProducer producer;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
        component = new KafkaCustomComponent();
        component.setCamelContext(camelContext);
        endpoint = new KafkaCustomEndpoint("kafka-custom:topicA", component);
        endpoint.setCamelContext(camelContext);
        endpoint.setConfiguration(configuration(false));
    }

    @Test
    void shouldReturnKafkaCustomComponentFromGetComponent() {
        assertSame(component, endpoint.getComponent());
    }

    @Test
    void shouldUseComponentKafkaClientFactoryWhenEndpointFactoryMissing() throws Exception {
        KafkaBGClientFactory factory = mock(KafkaBGClientFactory.class);
        component.setKafkaClientFactory(factory);

        endpoint.doBuild();

        assertSame(factory, endpoint.getKafkaClientFactory());
    }

    @Test
    void shouldKeepEndpointKafkaClientFactoryWhenAlreadyConfigured() throws Exception {
        KafkaBGClientFactory componentFactory = mock(KafkaBGClientFactory.class);
        KafkaBGClientFactory endpointFactory = mock(KafkaBGClientFactory.class);
        component.setKafkaClientFactory(componentFactory);
        endpoint.setKafkaClientFactory(endpointFactory);

        endpoint.doBuild();

        assertSame(endpointFactory, endpoint.getKafkaClientFactory());
    }

    @Test
    void shouldCreateKafkaBgConsumerWhenCreateConsumerCalled() throws Exception {
        Processor processor = mock(Processor.class);

        Consumer consumer = endpoint.createConsumer(processor);

        assertInstanceOf(KafkaBGConsumer.class, consumer);
        assertSame(endpoint, consumer.getEndpoint());
    }

    @Test
    void shouldReturnDelegateProducerWhenSynchronousDisabled() throws Exception {
        TestKafkaCustomEndpoint endpointWithProducer = endpointWithProducer(false, producer);

        Producer actual = endpointWithProducer.createProducer();

        assertSame(producer, actual);
        assertSame(endpointWithProducer, endpointWithProducer.capturedEndpoint);
    }

    @Test
    void shouldWrapProducerWhenSynchronousEnabled() throws Exception {
        TestKafkaCustomEndpoint endpointWithProducer = endpointWithProducer(true, producer);

        Producer actual = endpointWithProducer.createProducer();

        assertInstanceOf(SynchronousDelegateProducer.class, actual);
        assertSame(endpointWithProducer, endpointWithProducer.capturedEndpoint);
    }

    @Test
    void shouldReplaceKafkaClassNamePropertiesWithClasses() {
        Properties props = new Properties();
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, TestSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, TestSerializer.class.getName());
        props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, TestPartitioner.class.getName());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, TestDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, TestDeserializer.class.getName());
        props.put(CALLBACK_HANDLER_CLASS_CONFIG, TestCallbackHandler.class.getName());

        endpoint.updateClassProperties(props);

        assertSame(TestSerializer.class, props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertSame(TestSerializer.class, props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertSame(TestPartitioner.class, props.get(ProducerConfig.PARTITIONER_CLASS_CONFIG));
        assertSame(TestDeserializer.class, props.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertSame(TestDeserializer.class, props.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        assertSame(TestCallbackHandler.class, props.get(CALLBACK_HANDLER_CLASS_CONFIG));
    }

    @Test
    void shouldKeepPropertiesWhenCamelContextMissing() {
        KafkaCustomEndpoint kafkaCustomEndpoint = new KafkaCustomEndpoint();
        Properties props = new Properties();
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, TestSerializer.class.getName());

        kafkaCustomEndpoint.updateClassProperties(props);

        assertEquals(TestSerializer.class.getName(), props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
    }

    private TestKafkaCustomEndpoint endpointWithProducer(
            boolean synchronous, KafkaCustomProducer producer) {
        TestKafkaCustomEndpoint endpoint = new TestKafkaCustomEndpoint(
                "kafka-custom:topicA", component, producer);
        endpoint.setCamelContext(camelContext);
        endpoint.setConfiguration(configuration(synchronous));
        return endpoint;
    }

    private static KafkaCustomConfiguration configuration(boolean synchronous) {
        KafkaCustomConfiguration configuration = new KafkaCustomConfiguration();
        configuration.setTopic("topicA");
        configuration.setSynchronous(synchronous);
        return configuration;
    }

    private static final class TestKafkaCustomEndpoint extends KafkaCustomEndpoint {

        private final KafkaCustomProducer producer;
        private KafkaCustomEndpoint capturedEndpoint;

        private TestKafkaCustomEndpoint(
                String endpointUri, KafkaCustomComponent component, KafkaCustomProducer producer) {
            super(endpointUri, component);
            this.producer = producer;
        }

        @Override
        protected KafkaCustomProducer createProducer(KafkaCustomEndpoint endpoint) {
            capturedEndpoint = endpoint;
            return producer;
        }
    }

    private static final class TestSerializer implements Serializer<Object> {

        @Override
        public byte[] serialize(String topic, Object data) {
            return new byte[0];
        }
    }

    private static final class TestDeserializer implements Deserializer<Object> {

        @Override
        public Object deserialize(String topic, byte[] data) {
            return null;
        }
    }

    private static final class TestPartitioner implements Partitioner {

        @Override
        public void configure(Map<String, ?> configs) {
            // no-op
        }

        @Override
        public int partition(
                String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes,
                Cluster cluster) {
            return 0;
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class TestCallbackHandler implements AuthenticateCallbackHandler {

        @Override
        public void configure(
                Map<String, ?> configs, String saslMechanism,
                List<AppConfigurationEntry> jaasConfigEntries) {
        }

        @Override
        public void handle(Callback[] callbacks) {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
