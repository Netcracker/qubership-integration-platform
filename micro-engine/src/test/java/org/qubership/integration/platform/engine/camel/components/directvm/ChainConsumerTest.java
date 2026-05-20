package org.qubership.integration.platform.engine.camel.components.directvm;

import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainConsumerTest {

    private ChainConsumer consumer;
    private ChainComponent component;
    private ChainEndpoint endpoint;

    @BeforeEach
    void setUp() throws Exception {
        clearConsumersStaticMap();

        component = new ChainComponent();
        component.setCamelContext(new DefaultCamelContext());

        endpoint = new ChainEndpoint("cip-chain:routeA", component);
        Processor processor = mock(Processor.class);

        consumer = new ChainConsumer(endpoint, processor);
    }

    @Test
    void shouldRegisterConsumerInComponentWhenStarted() {
        consumer.start();

        assertSame(consumer, component.getConsumer(endpoint));
    }

    @Test
    void shouldUnregisterConsumerInComponentWhenStopped() {
        consumer.start();
        assertSame(consumer, component.getConsumer(endpoint));

        consumer.stop();
        assertNull(component.getConsumer(endpoint));
    }

    @Test
    void shouldUnregisterConsumerInComponentWhenSuspended() {
        consumer.start();
        assertSame(consumer, component.getConsumer(endpoint));

        consumer.suspend();
        assertNull(component.getConsumer(endpoint));
    }

    @Test
    void shouldRegisterConsumerInComponentWhenResumed() {
        consumer.start();
        consumer.suspend();
        assertNull(component.getConsumer(endpoint));

        consumer.resume();
        assertSame(consumer, component.getConsumer(endpoint));
    }

    @Test
    void shouldReturnChainEndpointWhenGetEndpointCalled() {
        assertSame(endpoint, consumer.getEndpoint());
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
