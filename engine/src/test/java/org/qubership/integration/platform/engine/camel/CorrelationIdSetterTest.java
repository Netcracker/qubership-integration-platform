package org.qubership.integration.platform.engine.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID_NAME;
import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID_POSITION;

class CorrelationIdSetterTest {

    private CorrelationIdSetter setter;
    private Exchange exchange;

    @BeforeEach
    void setUp() {
        setter = new CorrelationIdSetter(new ObjectMapper());
        exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(CORRELATION_ID_NAME, "zz-corr");
    }

    // The schema spells the positions in lowercase; chains from older exports store them capitalized.
    @ParameterizedTest
    @ValueSource(strings = {"header", "Header"})
    void readsTheCorrelationIdFromTheNamedHeader(String position) {
        exchange.setProperty(CORRELATION_ID_POSITION, position);
        exchange.getMessage().setHeader("zz-corr", "abc");

        setter.setCorrelationId(exchange);

        assertEquals("abc", exchange.getProperty(CORRELATION_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "Body"})
    void readsTheCorrelationIdFromTheNamedBodyField(String position) {
        exchange.setProperty(CORRELATION_ID_POSITION, position);
        exchange.getMessage().setBody("{\"zz-corr\":\"abc\"}");

        setter.setCorrelationId(exchange);

        assertEquals("abc", exchange.getProperty(CORRELATION_ID));
    }
}
