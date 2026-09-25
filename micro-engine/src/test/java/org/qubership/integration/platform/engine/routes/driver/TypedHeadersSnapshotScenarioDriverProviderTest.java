package org.qubership.integration.platform.engine.routes.driver;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TypedHeadersSnapshotScenarioDriverProviderTest {
    private final TypedHeadersSnapshotScenarioDriverProvider provider = new TypedHeadersSnapshotScenarioDriverProvider();

    @Mock
    private ProducerTemplate producerTemplate;

    private static final String ENDPOINT_URI = "direct:original-chain";
    private static final String TIMESTAMP_HEADER = "CamelSpringRabbitmqTimestamp";

    @Test
    void shouldCreateFreshDatesFromEpochMillisWhenInvocationIsRepeated() throws Exception {
        SnapshotScenarioDriver driver = driver();
        SnapshotScenarioInvocation invocation = invocation(1700000000123L);

        Date first = assertInstanceOf(Date.class, execute(driver, invocation).getMessage().getHeader(TIMESTAMP_HEADER));
        assertEquals(1700000000123L, first.getTime());
        first.setTime(0);
        Date second = assertInstanceOf(Date.class, execute(driver, invocation).getMessage().getHeader(TIMESTAMP_HEADER));

        assertNotSame(first, second);
        assertEquals(1700000000123L, second.getTime());
        assertEquals(1700000000123L, invocation.getHeaders().get(TIMESTAMP_HEADER));
    }

    @Test
    void shouldRejectInvalidEpochMillisBeforeCallingChain() {
        SnapshotScenarioDriver driver = driver();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> driver.execute(producerTemplate, invocation("not-a-timestamp")));

        assertEquals("Typed headers driver header 'CamelSpringRabbitmqTimestamp' cannot be represented as date.",
                exception.getMessage());
        verifyNoInteractions(producerTemplate);
    }

    private SnapshotScenarioDriver driver() {
        return provider.create(null, new SnapshotScenarioDriverDefinition("typedHeaders", null,
                Map.of("endpointUri", ENDPOINT_URI, "headerTypes", Map.of(TIMESTAMP_HEADER, "date"))), List.of());
    }

    private SnapshotScenarioInvocation invocation(Object timestamp) {
        return new SnapshotScenarioInvocation(
                "typed-headers", null, null, null, "message", Map.of(TIMESTAMP_HEADER, timestamp), Map.of(),
                null, null, null, null, null, null, null, null);
    }

    private Exchange execute(SnapshotScenarioDriver driver, SnapshotScenarioInvocation invocation) throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        when(producerTemplate.request(eq(ENDPOINT_URI), any(Processor.class))).thenAnswer(call -> {
            call.getArgument(1, Processor.class).process(exchange);
            return exchange;
        });
        return driver.execute(producerTemplate, invocation);
    }
}
