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

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import org.apache.camel.CamelContext;
import org.apache.camel.SSLContextParametersAware;
import org.apache.camel.component.kafka.DefaultKafkaClientFactory;
import org.apache.camel.component.kafka.PollExceptionStrategy;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.PropertiesHelper;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.camel.components.kafka.consumer.KafkaBGConsumer;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.DefaultKafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.factory.KafkaBGClientFactory;
import org.qubership.integration.platform.engine.camel.components.kafka.producer.KafkaCustomProducer;

import java.util.Map;

@Component("kafka-custom")
public class KafkaCustomComponent extends DefaultComponent implements SSLContextParametersAware {

    @Metadata
    private KafkaCustomConfiguration configuration = new KafkaCustomConfiguration();
    @Metadata(label = "security", defaultValue = "false")
    private boolean useGlobalSslContextParameters;
    @Metadata(autowired = true, label = "advanced")
    private KafkaBGClientFactory kafkaClientFactory;
    @Metadata(autowired = true, label = "consumer,advanced")
    private PollExceptionStrategy pollExceptionStrategy;

    @Metadata(autowired = true, label = "consumer,advanced", required = true)
    private BlueGreenStatePublisher blueGreenStatePublisher;

    public KafkaCustomComponent(CamelContext context) {
        super(context);
    }

    /**
     * BlueGreenStatePublisher
     */
    public void setBlueGreenStatePublisher(
            BlueGreenStatePublisher blueGreenStatePublisher) {
        this.blueGreenStatePublisher = blueGreenStatePublisher;
    }

    /**
     * BlueGreenStatePublisher
     */
    public BlueGreenStatePublisher getBlueGreenStatePublisher() {
        return blueGreenStatePublisher;
    }

    @Override
    protected KafkaCustomEndpoint createEndpoint(String uri, String remaining,
                                                 Map<String, Object> parameters) throws Exception {
        if (ObjectHelper.isEmpty(remaining)) {
            throw new IllegalArgumentException(
                    "Topic must be configured on endpoint using syntax kafka-custom:topic");
        }

        // extract the endpoint additional properties map
        final Map<String, Object> endpointAdditionalProperties
                = PropertiesHelper.extractProperties(parameters, "additionalProperties.");

        KafkaCustomEndpoint endpoint = new KafkaCustomEndpoint(uri, this);

        KafkaCustomConfiguration copy = getConfiguration().copy();
        endpoint.setConfiguration(copy);

        setProperties(endpoint, parameters);

        if (endpoint.getConfiguration().getSslContextParameters() == null) {
            endpoint.getConfiguration()
                    .setSslContextParameters(retrieveGlobalSslContextParameters());
        }

        // overwrite the additional properties from the endpoint
        if (!endpointAdditionalProperties.isEmpty()) {
            endpoint.getConfiguration().getAdditionalProperties()
                    .putAll(endpointAdditionalProperties);
        }

        // If a topic is not defined in the KafkaCustomConfiguration (set as option parameter) but only in the uri,
        // it can happen that it is not set correctly in the configuration of the endpoint.
        // Therefore, the topic is added after setProperties method
        // and a null check to avoid overwriting a value from the configuration.
        if (endpoint.getConfiguration().getTopic() == null) {
            endpoint.getConfiguration().setTopic(remaining);
        }

        return endpoint;
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

    @Override
    public boolean isUseGlobalSslContextParameters() {
        return this.useGlobalSslContextParameters;
    }

    /**
     * Enable usage of global SSL context parameters.
     */
    @Override
    public void setUseGlobalSslContextParameters(boolean useGlobalSslContextParameters) {
        this.useGlobalSslContextParameters = useGlobalSslContextParameters;
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

    public PollExceptionStrategy getPollExceptionStrategy() {
        return pollExceptionStrategy;
    }

    /**
     * To use a custom strategy with the consumer to control how to handle exceptions thrown from
     * the Kafka broker while pooling messages.
     */
    public void setPollExceptionStrategy(PollExceptionStrategy pollExceptionStrategy) {
        this.pollExceptionStrategy = pollExceptionStrategy;
    }

    @Override
    protected void doInit() throws Exception {
        super.doInit();

        // if a factory was not autowired then create a default factory
        if (kafkaClientFactory == null) {
            kafkaClientFactory = new DefaultKafkaBGClientFactory(new DefaultKafkaClientFactory(), blueGreenStatePublisher);
        }
    }
}
