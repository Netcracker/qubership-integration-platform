/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.camel.components.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.*;
import org.apache.camel.spi.ClassResolver;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.SynchronousDelegateProducer;
import org.apache.camel.util.CastUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.camel.components.kafka.consumer.KafkaBGConsumer;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.producer.KafkaCustomProducer;

import java.util.Properties;
import java.util.concurrent.ExecutorService;

/**
 * Sent and receive messages to/from an Apache Kafka broker.
 */
@Slf4j
@UriEndpoint(firstVersion = "2.13.0", scheme = "kafka-custom", title = "Kafka", syntax = "kafka-custom:topic",
        category = {Category.MESSAGING})
public class KafkaCustomEndpoint extends DefaultEndpoint implements MultipleConsumersSupport {

    private static final String CALLBACK_HANDLER_CLASS_CONFIG = "sasl.login.callback.handler.class";

    @UriParam
    private KafkaCustomConfiguration configuration = new KafkaCustomConfiguration();
    @UriParam(label = "advanced")
    private KafkaBGClientFactory kafkaClientFactory;

    public KafkaCustomEndpoint() {
    }

    public KafkaCustomEndpoint(String endpointUri, KafkaCustomComponent component) {
        super(endpointUri, component);
    }

    public KafkaCustomConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Allows to pre-configure the Kafka component with common options that the endpoints will
     * reuse.
     */
    public void setConfiguration(KafkaCustomConfiguration configuration) {
        this.configuration = configuration;
    }

    public KafkaBGClientFactory getKafkaClientFactory() {
        return kafkaClientFactory;
    }

    /**
     * Factory to use for creating {@link KafkaBGConsumer} and {@link KafkaCustomProducer}
     * instances. This allows to configure a custom factory to create instances with logic that
     * extends the vanilla Kafka clients.
     */
    public void setKafkaClientFactory(KafkaBGClientFactory kafkaClientFactory) {
        this.kafkaClientFactory = kafkaClientFactory;
    }

    @Override
    public KafkaCustomComponent getComponent() {
        return (KafkaCustomComponent) super.getComponent();
    }

    @Override
    protected void doBuild() throws Exception {
        super.doBuild();

        if (kafkaClientFactory == null) {
            kafkaClientFactory = getComponent().getKafkaClientFactory();
        }
    }

    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        KafkaBGConsumer consumer = new KafkaBGConsumer(this, processor);
        configureConsumer(consumer);
        return consumer;
    }

    @Override
    public Producer createProducer() throws Exception {
        KafkaCustomProducer producer = createProducer(this);
        if (getConfiguration().isSynchronous()) {
            return new SynchronousDelegateProducer(producer);
        } else {
            return producer;
        }
    }

    protected KafkaCustomProducer createProducer(KafkaCustomEndpoint endpoint) {
        return new KafkaCustomProducer(endpoint);
    }

    @Override
    public boolean isMultipleConsumersSupported() {
        return true;
    }

    <T> Class<T> loadClass(Object o, ClassResolver resolver, Class<T> type) {
        if (o == null || o instanceof Class) {
            return CastUtils.cast((Class<?>) o);
        }
        String name = o.toString();
        Class<T> c = resolver.resolveClass(name, type);
        if (c == null) {
            c = resolver.resolveClass(name, type, getClass().getClassLoader());
        }
        if (c == null) {
            c = resolver.resolveClass(name, type,
                    org.apache.kafka.clients.producer.KafkaProducer.class.getClassLoader());
        }
        return c;
    }

    void replaceWithClass(Properties props, String key, ClassResolver resolver, Class<?> type) {
        Class<?> c = loadClass(props.get(key), resolver, type);
        if (c != null) {
            props.put(key, c);
        }
    }

    public void updateClassProperties(Properties props) {
        try {
            if (getCamelContext() != null) {
                ClassResolver resolver = getCamelContext().getClassResolver();
                replaceWithClass(props, ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, resolver,
                        Serializer.class);
                replaceWithClass(props, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, resolver,
                        Serializer.class);
                replaceWithClass(props, ProducerConfig.PARTITIONER_CLASS_CONFIG, resolver,
                        Partitioner.class);
                replaceWithClass(props, ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, resolver,
                        Deserializer.class);
                replaceWithClass(props, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, resolver,
                        Deserializer.class);

                // because he property is not available in Kafka client, use a static string
                replaceWithClass(props, CALLBACK_HANDLER_CLASS_CONFIG, resolver,
                        AuthenticateCallbackHandler.class);
            }
        } catch (Exception t) {
            // can ignore and Kafka itself might be able to handle it, if not,
            // it will throw an exception
            log.debug("Problem loading classes for Serializers", t);
        }
    }

    public ExecutorService createExecutor() {
        return getCamelContext().getExecutorServiceManager().newFixedThreadPool(this,
                "KafkaConsumer[" + configuration.getTopic() + "]", configuration.getConsumersCount());
    }

    public ExecutorService createProducerExecutor() {
        int core = getConfiguration().getWorkerPoolCoreSize();
        int max = getConfiguration().getWorkerPoolMaxSize();
        return getCamelContext().getExecutorServiceManager().newThreadPool(this,
                "KafkaProducer[" + configuration.getTopic() + "]", core, max);
    }
}
