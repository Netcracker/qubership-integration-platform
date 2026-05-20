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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import org.apache.camel.component.kafka.KafkaClientFactory;
import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;

import java.util.Collection;
import java.util.Properties;

public class TaggedMetricsKafkaClientFactory implements KafkaClientFactory {
    private final KafkaClientFactory delegate;
    private final MeterRegistry meterRegistry;
    private final Collection<Tag> tags;

    public TaggedMetricsKafkaClientFactory(
        KafkaClientFactory delegate,
        MeterRegistry meterRegistry,
        Collection<Tag> tags
    ) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.tags = tags;
    }

    @Override
    public Producer getProducer(Properties kafkaProps) {
        Producer<?, ?> producer = delegate.getProducer(kafkaProps);
        new KafkaClientMetrics(producer, tags).bindTo(meterRegistry);
        return producer;
    }

    @Override
    public Consumer getConsumer(Properties kafkaProps) {
        Consumer<?, ?> consumer = delegate.getConsumer(kafkaProps);
        new KafkaClientMetrics(consumer, tags).bindTo(meterRegistry);
        return consumer;
    }

    @Override
    public String getBrokers(KafkaConfiguration configuration) {
        return delegate.getBrokers(configuration);
    }

}
