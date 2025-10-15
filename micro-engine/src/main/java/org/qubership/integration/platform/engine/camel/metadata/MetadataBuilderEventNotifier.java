package org.qubership.integration.platform.engine.camel.metadata;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Route;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.SimpleEventNotifierSupport;

import java.util.Map;
import java.util.Optional;

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ROUTE_METADATA_KEY;

@ApplicationScoped
@Unremovable
public class MetadataBuilderEventNotifier extends SimpleEventNotifierSupport {
    @Inject
    MetadataConverter metadataConverter;

    @Override
    public void notify(CamelEvent event) throws Exception {
        if (event instanceof CamelEvent.RouteAddedEvent routeAddedEvent) {
            Route route = routeAddedEvent.getRoute();
            buildMetadataProperties(route.getProperties());
            ((RouteDefinition) route.getRoute()).getRouteProperties()
                    .removeIf(prop -> ROUTE_METADATA_KEY.equals(prop.getKey()));
        }
    }

    private void buildMetadataProperties(final Map<String, Object> properties) {
        Optional.ofNullable(properties.get(ROUTE_METADATA_KEY))
                .map(String::valueOf)
                .map(metadataConverter::toMetadata)
                .ifPresent(value -> properties.put(ROUTE_METADATA_KEY, value));
    }
}
