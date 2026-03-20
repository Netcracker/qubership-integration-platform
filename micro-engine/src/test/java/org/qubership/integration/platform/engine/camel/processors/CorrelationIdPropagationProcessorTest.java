package org.qubership.integration.platform.engine.camel.processors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.BODY;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID_NAME;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID_POSITION;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.HEADER;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CorrelationIdPropagationProcessorTest {

    private static final String CORRELATION_ID_VALUE = "9c8d4f7e-2c18-4a9d-a5b7-61a0d36e4f21";
    private static final String CORRELATION_ID_NAME_VALUE = "correlationId";

    private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
    private final CorrelationIdPropagationProcessor processor =
            new CorrelationIdPropagationProcessor(objectMapper);

    @Test
    void shouldSetHeaderWhenCorrelationIdPresentAndPositionHeader() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(CORRELATION_ID, CORRELATION_ID_VALUE);
        exchange.setProperty(CORRELATION_ID_POSITION, HEADER);
        exchange.setProperty(CORRELATION_ID_NAME, CORRELATION_ID_NAME_VALUE);

        processor.process(exchange);

        assertEquals(
                CORRELATION_ID_VALUE,
                exchange.getMessage().getHeader(CORRELATION_ID_NAME_VALUE)
        );
    }

    @Test
    void shouldAddCorrelationIdToBodyWhenCorrelationIdPresentAndPositionBody() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(CORRELATION_ID, CORRELATION_ID_VALUE);
        exchange.setProperty(CORRELATION_ID_POSITION, BODY);
        exchange.setProperty(CORRELATION_ID_NAME, CORRELATION_ID_NAME_VALUE);
        exchange.getMessage().setBody("{\"name\":\"Alex\",\"role\":\"developer\"}");

        processor.process(exchange);

        Map<String, Object> body = objectMapper.readValue(
                exchange.getMessage().getBody(String.class),
                new TypeReference<>() {
                }
        );

        assertEquals("Alex", body.get("name"));
        assertEquals("developer", body.get("role"));
        assertEquals(CORRELATION_ID_VALUE, body.get(CORRELATION_ID_NAME_VALUE));
    }

    @Test
    void shouldNotPropagateWhenCorrelationIdAbsent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(CORRELATION_ID_POSITION, HEADER);
        exchange.setProperty(CORRELATION_ID_NAME, CORRELATION_ID_NAME_VALUE);

        processor.process(exchange);

        assertNull(exchange.getMessage().getHeader(CORRELATION_ID_NAME_VALUE));
    }

    @Test
    void shouldNotPropagateWhenCorrelationIdEqualsStringNull() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(CORRELATION_ID, "null");
        exchange.setProperty(CORRELATION_ID_POSITION, HEADER);
        exchange.setProperty(CORRELATION_ID_NAME, CORRELATION_ID_NAME_VALUE);

        processor.process(exchange);

        assertNull(exchange.getMessage().getHeader(CORRELATION_ID_NAME_VALUE));
    }

    @Test
    void shouldKeepBodyUnchangedWhenBodyIsInvalidJson() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(CORRELATION_ID, CORRELATION_ID_VALUE);
        exchange.setProperty(CORRELATION_ID_POSITION, BODY);
        exchange.setProperty(CORRELATION_ID_NAME, CORRELATION_ID_NAME_VALUE);
        exchange.getMessage().setBody("not-a-json");

        processor.process(exchange);

        assertEquals("not-a-json", exchange.getMessage().getBody(String.class));
    }
}
