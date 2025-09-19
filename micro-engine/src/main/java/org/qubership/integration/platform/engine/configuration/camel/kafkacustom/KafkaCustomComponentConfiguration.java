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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.camel.spi.ComponentCustomizer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomComponent;


@ApplicationScoped
public class KafkaCustomComponentConfiguration {
    @ConfigProperty(name = "camel.component.kafka.ssl-truststore-location")
    String sslTruststoreLocation;

    @ConfigProperty(name = "camel.component.kafka.ssl-truststore-password")
    String sslTruststorePassword;

    @ConfigProperty(name = "camel.component.kafka.ssl-truststore-type")
    String sslTruststoreType;

    @Produces
    @ApplicationScoped
    public ComponentCustomizer kafkaCustomComponentCustomizer() {
        return ComponentCustomizer.builder(KafkaCustomComponent.class)
            .build((component) -> {
                KafkaConfiguration config = component.getConfiguration();

                // copy only necessary properties
                config.setSslTruststoreLocation(sslTruststoreLocation);
                config.setSslTruststorePassword(sslTruststorePassword);
                config.setSslTruststoreType(sslTruststoreType);
            });
    }
}
