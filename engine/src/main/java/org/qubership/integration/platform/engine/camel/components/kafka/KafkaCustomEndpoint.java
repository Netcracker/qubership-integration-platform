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
import org.apache.camel.Category;
import org.apache.camel.MultipleConsumersSupport;
import org.apache.camel.component.kafka.KafkaComponent;
import org.apache.camel.component.kafka.KafkaEndpoint;
import org.apache.camel.spi.UriEndpoint;

/**
 * Sent and receive messages to/from an Apache Kafka broker.
 */
@Slf4j
@UriEndpoint(firstVersion = "2.13.0", scheme = "kafka-custom", title = "Kafka", syntax = "kafka-custom:topic",
    category = {Category.MESSAGING})
public class KafkaCustomEndpoint extends KafkaEndpoint implements MultipleConsumersSupport {

    public KafkaCustomEndpoint() {
    }

    public KafkaCustomEndpoint(String endpointUri, KafkaComponent component) {
        super(endpointUri, component);
    }
}
