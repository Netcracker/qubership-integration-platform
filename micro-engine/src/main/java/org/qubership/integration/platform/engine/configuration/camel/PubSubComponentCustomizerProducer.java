package org.qubership.integration.platform.engine.configuration.camel;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.apache.camel.component.google.pubsub.GooglePubsubComponent;
import org.apache.camel.spi.ComponentCustomizer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PubSubComponentCustomizerProducer {
    @Produces
    @LookupIfProperty(name = "qip.pubsub.emulator.enabled", stringValue = "true")
    public ComponentCustomizer servletCustomComponentCustomizer(
            @ConfigProperty(name = "qip.pubsub.emulator.address") String address
    ) {
        return ComponentCustomizer.builder(GooglePubsubComponent.class)
                .build((component) -> {
                    component.setEndpoint(address);
                });
    }
}
