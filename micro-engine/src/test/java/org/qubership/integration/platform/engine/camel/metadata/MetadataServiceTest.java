package org.qubership.integration.platform.engine.camel.metadata;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.model.RouteDefinition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MetadataServiceTest {

    @Mock
    MetadataConverter converter;

    @InjectMocks
    MetadataService service;

    @Test
    void shouldReturnMetadataWhenRouteHasRouteMetadataKey() {
        Route route = mock(Route.class);
        Metadata metadata = mock(Metadata.class);

        Map<String, Object> props = new HashMap<>();
        props.put(CamelConstants.ROUTE_METADATA_KEY, metadata);
        when(route.getProperties()).thenReturn(props);

        Optional<Metadata> result = service.getMetadata(route);

        assertTrue(result.isPresent());
        assertSame(metadata, result.get());
    }

    @Test
    void shouldReturnEmptyWhenRouteDoesNotHaveRouteMetadataKey() {
        Route route = mock(Route.class);

        Map<String, Object> props = new HashMap<>();
        when(route.getProperties()).thenReturn(props);

        Optional<Metadata> result = service.getMetadata(route);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnMetadataWhenExchangeResolvesRouteFromContext() {
        Exchange exchange = mock(Exchange.class);
        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Metadata metadata = mock(Metadata.class);

        when(exchange.getContext()).thenReturn(camelContext);
        when(exchange.getFromRouteId()).thenReturn("route-1");
        when(camelContext.getRoute("route-1")).thenReturn(route);

        Map<String, Object> props = new HashMap<>();
        props.put(CamelConstants.ROUTE_METADATA_KEY, metadata);
        when(route.getProperties()).thenReturn(props);

        Optional<Metadata> result = service.getMetadata(exchange);

        assertTrue(result.isPresent());
        assertSame(metadata, result.get());
        verify(camelContext).getRoute("route-1");
    }

    @Test
    void shouldReturnEmptyWhenCamelContextHasNoRouteForRouteId() {
        CamelContext camelContext = mock(CamelContext.class);
        when(camelContext.getRoute("missing-route")).thenReturn(null);

        Optional<Metadata> result = service.getMetadata(camelContext, "missing-route");

        assertTrue(result.isEmpty());
        verify(camelContext).getRoute("missing-route");
    }

    @Test
    void shouldReturnEmptyWhenRouteExistsButHasNoMetadata() {
        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);

        when(camelContext.getRoute("route-1")).thenReturn(route);
        when(route.getProperties()).thenReturn(new HashMap<>());

        Optional<Metadata> result = service.getMetadata(camelContext, "route-1");

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldSetRoutePropertyWithConvertedMetadataWhenSetMetadata() {
        RouteDefinition routeDefinition = mock(RouteDefinition.class);
        Metadata metadata = mock(Metadata.class);

        when(converter.toString(metadata)).thenReturn("meta-as-string");

        service.setMetadata(routeDefinition, metadata);

        verify(routeDefinition).routeProperty(CamelConstants.ROUTE_METADATA_KEY, "meta-as-string");
        verify(converter).toString(metadata);
    }
}
