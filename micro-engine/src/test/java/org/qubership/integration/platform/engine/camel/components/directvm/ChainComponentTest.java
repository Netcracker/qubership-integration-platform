package org.qubership.integration.platform.engine.camel.components.directvm;

import org.apache.camel.Endpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainComponentTest {

    private ChainComponent component;

    @BeforeEach
    void setUp() throws Exception {
        clearConsumersStaticMap();
        component = new ChainComponent();
        component.setCamelContext(new DefaultCamelContext());
    }

    @Test
    void shouldReturnNullWhenNoConsumersRegistered() {
        ChainEndpoint endpoint = endpoint("cip-chain:routeA");

        assertNull(component.getConsumer(endpoint));
    }

    @Test
    void shouldReturnLastConsumerWhenMultipleConsumersAdded() {
        ChainEndpoint endpoint = endpoint("cip-chain:routeA");

        ChainConsumer c1 = mock(ChainConsumer.class);
        ChainConsumer c2 = mock(ChainConsumer.class);

        component.addConsumer(endpoint, c1);
        component.addConsumer(endpoint, c2);

        assertSame(c2, component.getConsumer(endpoint));
    }

    @Test
    void shouldUseEndpointUriWithoutParametersAsConsumerKey() {
        ChainEndpoint endpointWithQuery1 = endpoint("cip-chain:routeA?x=1");
        ChainEndpoint endpointWithQuery2 = endpoint("cip-chain:routeA?y=2");

        ChainConsumer c1 = mock(ChainConsumer.class);
        component.addConsumer(endpointWithQuery1, c1);

        assertSame(c1, component.getConsumer(endpointWithQuery2));
    }

    @Test
    void shouldRemoveConsumerAndKeepOthers() {
        ChainEndpoint endpoint = endpoint("cip-chain:routeA");

        ChainConsumer c1 = mock(ChainConsumer.class);
        ChainConsumer c2 = mock(ChainConsumer.class);

        component.addConsumer(endpoint, c1);
        component.addConsumer(endpoint, c2);

        component.removeConsumer(endpoint, c2);
        assertSame(c1, component.getConsumer(endpoint));

        component.removeConsumer(endpoint, c1);
        assertNull(component.getConsumer(endpoint));
    }

    @Test
    void shouldNotFailWhenRemoveConsumerAndNoConsumersRegistered() {
        ChainEndpoint endpoint = endpoint("cip-chain:routeA");

        assertDoesNotThrow(() -> component.removeConsumer(endpoint, mock(ChainConsumer.class)));
        assertNull(component.getConsumer(endpoint));
    }

    @Test
    void shouldCreateEndpointWithComponentDefaultsWhenNoParametersProvided() throws Exception {
        component.setBlock(false);
        component.setTimeout(1234L);
        component.setPropagateProperties(false);

        Endpoint ep = component.createEndpoint("cip-chain:routeA", "routeA", new HashMap<>());

        assertInstanceOf(ChainEndpoint.class, ep);
        ChainEndpoint chainEndpoint = (ChainEndpoint) ep;

        assertEquals("cip-chain:routeA", chainEndpoint.getEndpointUri());
        assertFalse(chainEndpoint.isBlock());
        assertEquals(1234L, chainEndpoint.getTimeout());
        assertFalse(chainEndpoint.isPropagateProperties());
    }

    @Test
    void shouldCreateEndpointAndOverrideDefaultsWithParameters() throws Exception {
        component.setBlock(true);
        component.setTimeout(30000L);
        component.setPropagateProperties(true);

        Map<String, Object> params = Map.of(
                "block", false,
                "timeout", 10_000L,
                "propagateProperties", false
        );

        Endpoint ep = component.createEndpoint("cip-chain:routeA", "routeA", new HashMap<>(params));

        assertInstanceOf(ChainEndpoint.class, ep);
        ChainEndpoint chainEndpoint = (ChainEndpoint) ep;

        assertFalse(chainEndpoint.isBlock());
        assertEquals(10_000L, chainEndpoint.getTimeout());
        assertFalse(chainEndpoint.isPropagateProperties());
    }

    private ChainEndpoint endpoint(String uri) {
        return new ChainEndpoint(uri, component);
    }

    @SuppressWarnings("unchecked")
    private void clearConsumersStaticMap() throws Exception {
        Field f = ChainComponent.class.getDeclaredField("CONSUMERS");
        f.setAccessible(true);
        ConcurrentMap<String, List<ChainConsumer>> map =
                (ConcurrentMap<String, List<ChainConsumer>>) f.get(null);
        map.clear();
    }
}
