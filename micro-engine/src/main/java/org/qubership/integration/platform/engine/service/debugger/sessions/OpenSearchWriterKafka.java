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


import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.kafka.OpenSearchKafkaProducer;
import org.qubership.integration.platform.engine.model.opensearch.KafkaQueueElement;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;

@Slf4j
@ApplicationScoped
@IfBuildProperty(name = "qip.opensearch.kafka-client.enabled", stringValue = "true")
public class OpenSearchWriterKafka extends OpenSearchWriter {

    private final OpenSearchKafkaProducer openSearchKafkaProducer;

    @Inject
    public OpenSearchWriterKafka(OpenSearchKafkaProducer openSearchKafkaProducer) {
        this.openSearchKafkaProducer = openSearchKafkaProducer;
    }

    private void sendToKafka(SessionElementElastic element) {
        KafkaQueueElement kafkaQueueElement = KafkaQueueElement.builder()
                .id(element.getId())
                .source(element)
                .build();
        openSearchKafkaProducer.send(element.getId(), kafkaQueueElement);
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
