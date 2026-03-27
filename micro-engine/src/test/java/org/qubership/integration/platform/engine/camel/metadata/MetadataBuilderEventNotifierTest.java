package org.qubership.integration.platform.engine.camel.metadata;

import org.apache.camel.Route;
import org.apache.camel.model.PropertyDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ROUTE_METADATA_KEY;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MetadataBuilderEventNotifierTest {

    @Mock
    MetadataBuilderEventNotifier notifier;
    @Mock
    MetadataConverter metadataConverter;

    @BeforeEach
    void setUp() {
        metadataConverter = mock(MetadataConverter.class);
        notifier = notifier(metadataConverter);
    }

    @Test
    void shouldDoNothingWhenEventIsNotRouteAddedEvent() throws Exception {
        CamelEvent event = mock(CamelEvent.class);
        notifier.notify(event);
        verify(metadataConverter, never()).toMetadata(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldConvertRouteMetadataAndRemoveRoutePropertyWhenRouteAddedEventReceived() throws Exception {
        CamelEvent.RouteAddedEvent event = mock(CamelEvent.RouteAddedEvent.class);
        Route route = mock(Route.class);
        RouteDefinition routeDefinition = mock(RouteDefinition.class);
        Metadata metadata = mock(Metadata.class);

        Map<String, Object> properties = new HashMap<>();
        properties.put(ROUTE_METADATA_KEY, "{\"name\":\"test\"}");
        properties.put("another", "value");

        PropertyDefinition metadataProperty = mock(PropertyDefinition.class);
        when(metadataProperty.getKey()).thenReturn(ROUTE_METADATA_KEY);

        PropertyDefinition anotherProperty = mock(PropertyDefinition.class);
        when(anotherProperty.getKey()).thenReturn("another");

        List<PropertyDefinition> routeProperties = new ArrayList<>();
        routeProperties.add(metadataProperty);
        routeProperties.add(anotherProperty);

        when(event.getRoute()).thenReturn(route);
        when(route.getProperties()).thenReturn(properties);
        when(route.getRoute()).thenReturn(routeDefinition);
        when(routeDefinition.getRouteProperties()).thenReturn(routeProperties);
        when(metadataConverter.toMetadata("{\"name\":\"test\"}")).thenReturn(metadata);

        notifier.notify(event);

        assertSame(metadata, properties.get(ROUTE_METADATA_KEY));
        assertEquals("value", properties.get("another"));
        assertEquals(1, routeProperties.size());
        assertSame(anotherProperty, routeProperties.get(0));

        verify(metadataConverter).toMetadata("{\"name\":\"test\"}");
    }

    @Test
    void shouldOnlyRemoveRoutePropertyWhenMetadataMissingInProperties() throws Exception {
        CamelEvent.RouteAddedEvent event = mock(CamelEvent.RouteAddedEvent.class);
        Route route = mock(Route.class);
        RouteDefinition routeDefinition = mock(RouteDefinition.class);

        Map<String, Object> properties = new HashMap<>();
        properties.put("another", "value");

        PropertyDefinition metadataProperty = mock(PropertyDefinition.class);
        when(metadataProperty.getKey()).thenReturn(ROUTE_METADATA_KEY);

        PropertyDefinition anotherProperty = mock(PropertyDefinition.class);
        when(anotherProperty.getKey()).thenReturn("another");

        List<PropertyDefinition> routeProperties = new ArrayList<>();
        routeProperties.add(metadataProperty);
        routeProperties.add(anotherProperty);

        when(event.getRoute()).thenReturn(route);
        when(route.getProperties()).thenReturn(properties);
        when(route.getRoute()).thenReturn(routeDefinition);
        when(routeDefinition.getRouteProperties()).thenReturn(routeProperties);

        notifier.notify(event);

        assertEquals("value", properties.get("another"));
        assertEquals(1, routeProperties.size());
        assertSame(anotherProperty, routeProperties.get(0));

        verify(metadataConverter, never()).toMetadata(org.mockito.ArgumentMatchers.anyString());
    }

    private static MetadataBuilderEventNotifier notifier(MetadataConverter metadataConverter) {
        MetadataBuilderEventNotifier notifier = new MetadataBuilderEventNotifier();
        notifier.metadataConverter = metadataConverter;
        return notifier;
    }
}
