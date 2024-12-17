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

package org.qubership.integration.platform.engine.camel.components.rabbitmq;

import static org.apache.camel.component.springrabbit.SpringRabbitMQEndpoint.ARG_PREFIX;

import java.util.Map;
import javax.net.ssl.TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Endpoint;
import org.apache.camel.component.springrabbit.SpringRabbitMQComponent;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.util.PropertiesHelper;

@Slf4j
@Component("rabbitmq-custom")
@SuppressWarnings("unused")
public class SpringRabbitMQCustomComponent extends SpringRabbitMQComponent {

    @Metadata(label = "security")
    private TrustManager trustManager;

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters)
        throws Exception {
        SpringRabbitMQCustomEndpoint endpoint = new SpringRabbitMQCustomEndpoint(uri, this,
            remaining);

        endpoint.setConnectionFactory(getConnectionFactory());

        TrustManager trustManager
            = resolveAndRemoveReferenceParameter(parameters, "trustManager", TrustManager.class,
            getTrustManager());
        endpoint.setTrustManager(trustManager);

        endpoint.setTestConnectionOnStartup(isTestConnectionOnStartup());
        endpoint.setMessageConverter(getMessageConverter());
        getMessagePropertiesConverter().setHeaderFilterStrategy(getHeaderFilterStrategy());
        endpoint.setMessagePropertiesConverter(getMessagePropertiesConverter());
        endpoint.setAutoStartup(isAutoStartup());
        endpoint.setAutoDeclare(isAutoDeclare());
        endpoint.setDeadLetterExchange(getDeadLetterExchange());
        endpoint.setDeadLetterExchangeType(getDeadLetterExchangeType());
        endpoint.setDeadLetterQueue(getDeadLetterQueue());
        endpoint.setDeadLetterRoutingKey(getDeadLetterRoutingKey());
        endpoint.setReplyTimeout(getReplyTimeout());
        endpoint.setPrefetchCount(getPrefetchCount());
        endpoint.setMessageListenerContainerType(getMessageListenerContainerType());
        endpoint.setConcurrentConsumers(getConcurrentConsumers());
        endpoint.setMaxConcurrentConsumers(getMaxConcurrentConsumers());
        endpoint.setRetry(getRetry());
        endpoint.setMaximumRetryAttempts(getMaximumRetryAttempts());
        endpoint.setRetryDelay(getRetryDelay());
        endpoint.setRejectAndDontRequeue(isRejectAndDontRequeue());
        endpoint.setAllowNullBody(isAllowNullBody());

        endpoint.setArgs(PropertiesHelper.extractProperties(parameters, ARG_PREFIX));
        setProperties(endpoint, parameters);

        return endpoint;
    }

    public TrustManager getTrustManager() {
        return trustManager;
    }

    /**
     * Configure SSL trust manager, SSL should be enabled for this option to be effective
     */
    public void setTrustManager(TrustManager trustManager) {
        this.trustManager = trustManager;
    }
}
