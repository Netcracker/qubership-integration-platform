package org.qubership.integration.platform.engine.camel.metadata;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.model.RouteDefinition;

import java.util.Collection;
import java.util.Optional;

import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ROUTE_METADATA_KEY;

@ApplicationScoped
public class MetadataService {
    @Inject
    MetadataConverter converter;

    public Optional<Metadata> getMetadata(Route route) {
        return Optional.ofNullable(route.getProperties().get(ROUTE_METADATA_KEY))
                .map(Metadata.class::cast);
    }

    public Optional<Metadata> getMetadata(Exchange exchange) {
        return getMetadata(exchange.getContext(), exchange.getFromRouteId());
    }

    public Optional<Metadata> getMetadata(CamelContext camelContext, String routeId) {
        return Optional.ofNullable(camelContext.getRoute(routeId)).flatMap(this::getMetadata);
    }

    public void setMetadata(Collection<RouteDefinition> routes, Metadata metadata) {
        routes.forEach(route -> setMetadata(route, metadata));
    }

    public void setMetadata(RouteDefinition routeDefinition, Metadata metadata) {
        String value = converter.toString(metadata);
        routeDefinition.routeProperty(ROUTE_METADATA_KEY, value);
    }
}
