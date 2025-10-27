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

package org.qubership.integration.platform.engine.service.debugger.sessions;


import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "qip.opensearch.kafka-client.enabled", havingValue = "true")
public class OpenSearchWriterKafka extends OpenSearchWriter {

    private final KafkaTemplate<String, KafkaQueueElement> openSearchKafkaTemplate;

    @Value("${qip.opensearch.kafka-client.topic:}")
    private String kafkaClientTopic;

    @Autowired
    public OpenSearchWriterKafka(@Qualifier("openSearchKafkaTemplate") KafkaTemplate<String, KafkaQueueElement> openSearchKafkaTemplate) {
        this.openSearchKafkaTemplate = openSearchKafkaTemplate;
    }

    private void sendToKafka(SessionElementElastic element) {
        try {
            KafkaQueueElement kafkaQueueElement = KafkaQueueElement.builder()
                    .id(element.getId())
                    .source(element)
                    .build();

            openSearchKafkaTemplate.send(kafkaClientTopic, element.getId(), kafkaQueueElement);
        } catch (Exception e) {
            log.error("Unable to send element to opensearch via kafka", e);
        }
    }

    @Override
    public void scheduleElementToLog(SessionElementElastic element) {
        scheduleElementToLog(element, false);
    }

    protected void scheduleElementToLog(SessionElementElastic element, boolean addToCache) {
        sendToKafka(element);

        if (addToCache) {
            putSessionElementToCache(element);
        }
    }
}
