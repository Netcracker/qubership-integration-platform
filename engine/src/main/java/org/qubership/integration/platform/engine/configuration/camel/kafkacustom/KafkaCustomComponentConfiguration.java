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

package org.qubership.integration.platform.engine.configuration.camel.kafkacustom;

import org.apache.camel.component.kafka.springboot.KafkaComponentConfiguration;
import org.apache.camel.spi.ComponentCustomizer;
import org.apache.camel.spring.boot.ComponentConfigurationProperties;
import org.apache.camel.spring.boot.util.ConditionalOnHierarchicalProperties;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomComponent;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties({
    ComponentConfigurationProperties.class,
    KafkaComponentConfiguration.class
})
@ConditionalOnHierarchicalProperties({"camel.component", "camel.component.kafka"})
public class KafkaCustomComponentConfiguration {

    private final KafkaComponentConfiguration configuration;
    private final List<String> legacyHeaderNames;

    @Autowired
    public KafkaCustomComponentConfiguration(KafkaComponentConfiguration configuration,
        @Value("${cip.camel.component.kafka.legacy-header-names}") List<String> legacyHeaderNames) {
        this.configuration = configuration;
        this.legacyHeaderNames = legacyHeaderNames;
    }

    @Bean
    public ComponentCustomizer kafkaCustomComponentCustomizer() {
        return ComponentCustomizer.builder(KafkaCustomComponent.class)
            .build((component) -> {
                KafkaCustomConfiguration config = component.getConfiguration();

                // copy only necessary properties
                config.setSslTruststoreLocation(configuration.getSslTruststoreLocation());
                config.setSslTruststorePassword(configuration.getSslTruststorePassword());
                config.setSslTruststoreType(configuration.getSslTruststoreType());
                config.setLegacyHeaderNames(legacyHeaderNames);
            });
    }
}
