package org.qubership.integration.platform.engine.camel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ChainsAggregationStrategyTest {

    private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
    private final ChainsAggregationStrategy strategy = new ChainsAggregationStrategy(objectMapper);

    @Test
    void shouldReturnNullWhenFirstExchangeFailed() {
        Exchange newExchange = createBranchExchange("customerDetails", "{\"customerId\":\"C-100500\"}");
        Exchange inputExchange = MockExchanges.defaultExchange();

        newExchange.setException(new IllegalStateException("boom"));

        Exchange result = strategy.aggregate(null, newExchange, inputExchange);

        assertNull(result);
    }

    @Test
    void shouldReturnOldExchangeWhenNextExchangeFailed() {
        Exchange oldExchange = createBranchExchange("customerDetails", "{\"customerId\":\"C-100500\"}");
        Exchange newExchange = createBranchExchange("orderSummary", "{\"orderId\":\"O-456\"}");
        Exchange inputExchange = MockExchanges.defaultExchange();

        newExchange.setException(new IllegalStateException("boom"));

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertSame(oldExchange, result);
    }

    @Test
    void shouldWrapFirstBranchPayloadAndMarkExchangeProcessedWhenFirstSuccessfulExchangeAggregated() {
        Exchange newExchange = createBranchExchange("customerDetails", "{\"customerId\":\"C-100500\"}");

        Exchange result = strategy.aggregate(null, newExchange);

        JsonNode body = (JsonNode) result.getMessage().getBody();

        assertEquals("C-100500", body.get("customerDetails").get("customerId").asText());
        assertEquals("200", result.getMessage().getHeader(Headers.CAMEL_HTTP_RESPONSE_CODE));
        assertEquals(true, result.getProperty(Properties.SPLIT_PROCESSED, Boolean.class));
    }

    @Test
    void shouldAppendNextBranchPayloadAndClearBranchNameWhenAdditionalExchangeAggregated() {
        Exchange aggregatedExchange = createBranchExchange("customerDetails", "{\"customerId\":\"C-100500\"}");
        Exchange newExchange = createBranchExchange("orderSummary", "{\"orderId\":\"O-456\"}");

        Exchange firstResult = strategy.aggregate(null, aggregatedExchange);
        Exchange result = strategy.aggregate(firstResult, newExchange);

        JsonNode body = (JsonNode) result.getMessage().getBody();

        assertEquals("C-100500", body.get("customerDetails").get("customerId").asText());
        assertEquals("O-456", body.get("orderSummary").get("orderId").asText());
        assertNull(result.getProperty(Properties.SPLIT_ID));
        assertNull(result.getProperty(Properties.SPLIT_ID_CHAIN));
    }

    @Test
    void shouldStoreTextNodeWhenBranchPayloadNotJson() {
        Exchange newExchange = createBranchExchange("customerDetails", "plain-text-response");

        Exchange result = strategy.aggregate(null, newExchange);

        JsonNode body = (JsonNode) result.getMessage().getBody();

        assertEquals("plain-text-response", body.get("customerDetails").asText());
        assertEquals("200", result.getMessage().getHeader(Headers.CAMEL_HTTP_RESPONSE_CODE));
    }

    @Test
    void shouldPropagateHeadersWithBranchPrefixAndSkipInternalHeadersWhenHeaderPropagationEnabled() {
        Exchange oldExchange = createBranchExchange("customerDetails", "{}");
        Exchange newExchange = createBranchExchange("orderSummary", "{}");
        Exchange inputExchange = MockExchanges.defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_PROPAGATE_HEADERS, true);
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_HEADERS, true);

        oldExchange.getMessage().setHeader("X-Customer-Id", "C-100500");
        oldExchange.getMessage().setHeader(Headers.HTTP_URI, "/internal/customer");

        newExchange.getMessage().setHeader("X-Order-Id", "O-456");
        newExchange.getMessage().setHeader(Headers.EXTERNAL_SESSION_CIP_ID, "external-session-id");

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals("C-100500", result.getMessage().getHeader("customerDetails.X-Customer-Id"));
        assertEquals("O-456", result.getMessage().getHeader("orderSummary.X-Order-Id"));
        assertNull(result.getMessage().getHeader("customerDetails." + Headers.HTTP_URI));
        assertNull(result.getMessage().getHeader("orderSummary." + Headers.EXTERNAL_SESSION_CIP_ID));
    }

    @Test
    void shouldPropagateHeadersWithoutPrefixWhenMainBranchAggregated() {
        Exchange oldExchange = createBranchExchange("mainResponse", "{}");
        Exchange newExchange = createBranchExchange("orderSummary", "{}");
        Exchange inputExchange = MockExchanges.defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        oldExchange.getMessage().setHeader("X-Correlation-Id", "REQ-42");

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals("REQ-42", result.getMessage().getHeader("X-Correlation-Id"));
        assertNull(result.getMessage().getHeader("mainResponse.X-Correlation-Id"));
    }

    @Test
    void shouldPropagatePropertiesWithBranchPrefixAndSkipInternalAndVariablesPropertiesWhenPropertyPropagationEnabled() {
        Exchange oldExchange = createBranchExchange("customerDetails", "{}");
        Exchange newExchange = createBranchExchange("orderSummary", "{}");
        Exchange inputExchange = MockExchanges.defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);

        oldExchange.setProperty("customerId", "C-100500");
        oldExchange.setProperty(Properties.VARIABLES_PROPERTY_MAP_NAME, new HashMap<>(Map.of("customerId", "C-100500")));
        oldExchange.setProperty(Properties.SESSION_ID, "e1e7fa4a-35a9-4b26-8b55-c7d5dd83d101");

        newExchange.setProperty("orderId", "O-456");
        newExchange.setProperty(Properties.VARIABLES_PROPERTY_MAP_NAME, new HashMap<>(Map.of("orderId", "O-456")));
        newExchange.setProperty(Properties.SESSION_ID, "f9d8a182-3c57-4a36-b4d2-d6cb8ad7f102");

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals("C-100500", result.getProperty("customerDetails.customerId"));
        assertEquals("O-456", result.getProperty("orderSummary.orderId"));

        assertNull(result.getProperty("customerDetails." + Properties.VARIABLES_PROPERTY_MAP_NAME));
        assertNull(result.getProperty("orderSummary." + Properties.VARIABLES_PROPERTY_MAP_NAME));
        assertNull(result.getProperty("customerDetails." + Properties.SESSION_ID));
        assertNull(result.getProperty("orderSummary." + Properties.SESSION_ID));
    }

    private Exchange createBranchExchange(String branchName, String body) {
        Exchange exchange = MockExchanges.defaultExchange();
        exchange.setProperty(Properties.SPLIT_ID, branchName);
        exchange.getMessage().setBody(body);
        return exchange;
    }
}
