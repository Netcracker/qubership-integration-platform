package org.qubership.integration.platform.engine.camel.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID_NAME;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID_POSITION;

class CorrelationIdPropagationProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CorrelationIdPropagationProcessor processor;
    private Exchange exchange;

    @BeforeEach
    void setUp() {
        processor = new CorrelationIdPropagationProcessor(objectMapper);
        exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(CORRELATION_ID, "abc");
        exchange.setProperty(CORRELATION_ID_NAME, "zz-corr");
    }

    // The schema spells the positions in lowercase; chains from older exports store them capitalized.
    @ParameterizedTest
    @ValueSource(strings = {"header", "Header"})
    void writesTheCorrelationIdToTheNamedHeader(String position) {
        exchange.setProperty(CORRELATION_ID_POSITION, position);

        processor.process(exchange);

        assertEquals("abc", exchange.getMessage().getHeader("zz-corr"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "Body"})
    void writesTheCorrelationIdToTheNamedBodyField(String position) throws Exception {
        exchange.setProperty(CORRELATION_ID_POSITION, position);
        exchange.getMessage().setBody("{\"name\":\"order\"}");

        processor.process(exchange);

        JsonNode body = objectMapper.readTree(exchange.getMessage().getBody(String.class));
        assertEquals("abc", body.get("zz-corr").asText());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void leavesAnEmptyBodyAsIs(String payload) {
        exchange.setProperty(CORRELATION_ID_POSITION, "body");
        exchange.getMessage().setBody(payload);

        processor.process(exchange);

        assertEquals(payload, exchange.getMessage().getBody(String.class));
    }
}
