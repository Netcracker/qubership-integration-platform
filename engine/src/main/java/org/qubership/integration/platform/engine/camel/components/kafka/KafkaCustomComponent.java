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

import java.util.HashMap;
import java.util.Map;
import org.apache.camel.SSLContextParametersAware;
import org.apache.camel.component.kafka.KafkaComponent;
import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.camel.component.kafka.KafkaEndpoint;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.PropertyBindingSupport;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.PropertiesHelper;

@Component("kafka-custom")
public class KafkaCustomComponent extends KafkaComponent implements SSLContextParametersAware {

    @Override
    protected KafkaEndpoint createEndpoint(String uri, String remaining,
        Map<String, Object> parameters) throws Exception {
        if (ObjectHelper.isEmpty(remaining)) {
            throw new IllegalArgumentException(
                "Topic must be configured on endpoint using syntax kafka:topic");
        } else {
            Map<String, Object> endpointAdditionalProperties = PropertiesHelper.extractProperties(
                parameters, "additionalProperties.");
            KafkaEndpoint endpoint = new KafkaCustomEndpoint(uri, this);
            KafkaConfiguration copy = this.getConfiguration().copy();
            endpoint.setConfiguration(copy);
            this.setProperties(endpoint, parameters);
            if (endpoint.getConfiguration().getSslContextParameters() == null) {
                endpoint.getConfiguration()
                    .setSslContextParameters(this.retrieveGlobalSslContextParameters());
            }

            if (!endpointAdditionalProperties.isEmpty()) {
                Map<String, Object> map = new HashMap();
                PropertyBindingSupport.bindProperties(this.getCamelContext(), map,
                    endpointAdditionalProperties);
                endpoint.getConfiguration().getAdditionalProperties().putAll(map);
            }

            if (endpoint.getConfiguration().getTopic() == null) {
                endpoint.getConfiguration().setTopic(remaining);
            }

            return endpoint;
        }
    }
}
