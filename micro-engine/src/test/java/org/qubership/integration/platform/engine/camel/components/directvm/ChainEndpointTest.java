package org.qubership.integration.platform.engine.camel.components.directvm;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainEndpointTest {

    private ChainEndpoint endpoint;
    private ChainComponent component;

    @BeforeEach
    void setUp() {
        component = spy(new ChainComponent());
        component.setCamelContext(new DefaultCamelContext());
        endpoint = new ChainEndpoint("cip-chain:routeA", component);
    }

    @Test
    void shouldReturnChainComponentFromGetComponent() {
        assertSame(component, endpoint.getComponent());
    }

    @Test
    void shouldCreateBlockingProducerWhenBlockTrue() throws Exception {
        endpoint.setBlock(true);

        Producer producer = endpoint.createProducer();

        assertInstanceOf(ChainBlockingProducer.class, producer);
    }

    @Test
    void shouldCreateNonBlockingProducerWhenBlockFalse() throws Exception {
        endpoint.setBlock(false);

        Producer producer = endpoint.createProducer();

        assertInstanceOf(ChainProducer.class, producer);
    }

    @Test
    void shouldCreateChainConsumerWithWrappedProcessorWhenCreateConsumer() throws Exception {
        Processor processor = mock(Processor.class);

        Consumer consumer = endpoint.createConsumer(processor);

        assertNotNull(consumer);
        assertInstanceOf(ChainConsumer.class, consumer);
    }

    @Test
    void shouldGetConsumerFromComponent() {
        ChainConsumer expected = mock(ChainConsumer.class);
        doReturn(expected).when(component).getConsumer(endpoint);

        ChainConsumer actual = endpoint.getConsumer();

        assertSame(expected, actual);
    }

    @Test
    void shouldReturnComponentHeaderFilterStrategyWhenEndpointHeaderFilterStrategyNull() {
        var componentStrategy = mock(org.apache.camel.spi.HeaderFilterStrategy.class);
        component.setHeaderFilterStrategy(componentStrategy);

        assertSame(componentStrategy, endpoint.getHeaderFilterStrategy());
    }

    @Test
    void shouldReturnEndpointHeaderFilterStrategyWhenSetOnEndpoint() {
        var endpointStrategy = mock(org.apache.camel.spi.HeaderFilterStrategy.class);
        endpoint.setHeaderFilterStrategy(endpointStrategy);

        assertSame(endpointStrategy, endpoint.getHeaderFilterStrategy());
    }

    @Test
    void shouldSetAndGetTimeoutBlockFailIfNoConsumersAndPropagateProperties() {
        endpoint.setTimeout(42L);
        endpoint.setBlock(false);
        endpoint.setFailIfNoConsumers(false);
        endpoint.setPropagateProperties(false);

        assertEquals(42L, endpoint.getTimeout());
        assertFalse(endpoint.isBlock());
        assertFalse(endpoint.isFailIfNoConsumers());
        assertFalse(endpoint.isPropagateProperties());
    }
}
