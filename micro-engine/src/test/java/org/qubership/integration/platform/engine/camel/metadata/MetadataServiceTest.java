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
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MetadataServiceTest {

    @InjectMocks
    MetadataService service;

    @Mock
    MetadataConverter converter;
    @Mock
    Route route;
    @Mock
    Metadata metadata;
    @Mock
    CamelContext camelContext;

    @Test
    void shouldReturnMetadataWhenRouteHasRouteMetadataKey() {
        Map<String, Object> props = new HashMap<>();
        props.put(CamelConstants.ROUTE_METADATA_KEY, metadata);
        when(route.getProperties()).thenReturn(props);

        Optional<Metadata> result = service.getMetadata(route);

        assertTrue(result.isPresent());
        assertSame(metadata, result.get());
    }

    @Test
    void shouldReturnEmptyWhenRouteDoesNotHaveRouteMetadataKey() {
        Map<String, Object> props = new HashMap<>();
        when(route.getProperties()).thenReturn(props);

        Optional<Metadata> result = service.getMetadata(route);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnMetadataWhenExchangeResolvesRouteFromContext() {
        Exchange exchange = MockExchanges.basic();

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
        when(camelContext.getRoute("missing-route")).thenReturn(null);

        Optional<Metadata> result = service.getMetadata(camelContext, "missing-route");

        assertTrue(result.isEmpty());
        verify(camelContext).getRoute("missing-route");
    }

    @Test
    void shouldReturnEmptyWhenRouteExistsButHasNoMetadata() {
        when(camelContext.getRoute("route-1")).thenReturn(route);
        when(route.getProperties()).thenReturn(new HashMap<>());

        Optional<Metadata> result = service.getMetadata(camelContext, "route-1");

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldSetRoutePropertyWithConvertedMetadataWhenSetMetadata() {
        RouteDefinition routeDefinition = mock(RouteDefinition.class);

        when(converter.toString(metadata)).thenReturn("meta-as-string");

        service.setMetadata(routeDefinition, metadata);

        verify(routeDefinition).routeProperty(CamelConstants.ROUTE_METADATA_KEY, "meta-as-string");
        verify(converter).toString(metadata);
    }
}
