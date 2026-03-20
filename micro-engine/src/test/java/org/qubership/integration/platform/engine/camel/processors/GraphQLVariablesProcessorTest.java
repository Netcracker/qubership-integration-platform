package org.qubership.integration.platform.engine.camel.processors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.util.json.JsonObject;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GraphQLVariablesProcessorTest {

    private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
    private final GraphQLVariablesProcessor processor = new GraphQLVariablesProcessor(objectMapper);

    @Test
    void shouldSetGraphQlVariablesHeaderWhenVariablesJsonPresent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        String variablesJson = "{\"customerId\":\"C-100500\",\"active\":true}";

        exchange.setProperty(Properties.GQL_VARIABLES_JSON, variablesJson);

        processor.process(exchange);

        JsonObject expected = objectMapper.readValue(variablesJson, JsonObject.class);
        JsonObject actual = exchange.getMessage().getHeader(Headers.GQL_VARIABLES_HEADER, JsonObject.class);

        assertEquals(expected, actual);
    }

    @Test
    void shouldSetEmptyGraphQlVariablesHeaderWhenVariablesJsonNull() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        processor.process(exchange);

        JsonObject actual = exchange.getMessage().getHeader(Headers.GQL_VARIABLES_HEADER, JsonObject.class);

        assertNotNull(actual);
        assertEquals(new JsonObject(), actual);
    }

    @Test
    void shouldSetEmptyGraphQlVariablesHeaderWhenVariablesJsonEmpty() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(Properties.GQL_VARIABLES_JSON, "");

        processor.process(exchange);

        JsonObject actual = exchange.getMessage().getHeader(Headers.GQL_VARIABLES_HEADER, JsonObject.class);

        assertNotNull(actual);
        assertEquals(new JsonObject(), actual);
    }
}
