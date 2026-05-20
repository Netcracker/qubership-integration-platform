package org.qubership.integration.platform.engine.configuration.camel;

import org.apache.camel.component.google.pubsub.GooglePubsubComponent;
import org.apache.camel.spi.ComponentCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CamelPubSubConfiguration {
    @Bean
    @ConditionalOnProperty(name = "qip.pubsub.emulator.enabled", havingValue = "true")
    public ComponentCustomizer servletCustomComponentCustomizer(
            @Value("${qip.pubsub.emulator.address}") String address
    ) {
        return ComponentCustomizer.builder(GooglePubsubComponent.class)
                .build((component) -> {
                    component.setEndpoint(address);
                });
    }
}
