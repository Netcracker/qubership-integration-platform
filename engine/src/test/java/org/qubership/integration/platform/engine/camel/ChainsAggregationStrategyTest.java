/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.camel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeExtension;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChainsAggregationStrategyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChainsAggregationStrategy strategy = new ChainsAggregationStrategy(objectMapper);

    @Test
    void shouldReturnNullWhenFirstExchangeFailed() {
        Exchange newExchange = createBranchExchange("customerDetails", "{\"customerId\":\"C-100500\"}");
        Exchange inputExchange = defaultExchange();

        newExchange.setException(new IllegalStateException("boom"));

        assertNull(strategy.aggregate(null, newExchange, inputExchange));
    }

    @Test
    void shouldReturnOldExchangeWhenNextExchangeFailed() {
        Exchange oldExchange = createBranchExchange("customerDetails", "{\"customerId\":\"C-100500\"}");
        Exchange newExchange = createBranchExchange("orderSummary", "{\"orderId\":\"O-456\"}");
        Exchange inputExchange = defaultExchange();

        newExchange.setException(new IllegalStateException("boom"));

        assertSame(oldExchange, strategy.aggregate(oldExchange, newExchange, inputExchange));
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

        strategy.aggregate(null, aggregatedExchange);
        Exchange result = strategy.aggregate(aggregatedExchange, newExchange);

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
        Exchange inputExchange = defaultExchange();

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
        Exchange inputExchange = defaultExchange();

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
        Exchange inputExchange = defaultExchange();

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

    @Test
    void shouldPropagatePropertiesWithBranchPrefixWhenExchangePropertiesAlreadyProcessed() {
        Exchange oldExchange = createBranchExchange("mainBranch", "{}");
        Exchange newExchange = createBranchExchange("warmup", "{}");
        Exchange inputExchange = defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        oldExchange.setProperty("mainProp", "from-main");

        newExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "secondary");
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        newExchange.setProperty("testProp", "from-secondary");
        newExchange.setProperty(Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED, true);

        inputExchange.setProperty(Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED, true);

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals("from-main", result.getProperty("mainProp"));
        assertEquals("from-secondary", result.getProperty("warmup.testProp"));
        assertNull(result.getProperty("testProp"));
    }

    @Test
    void shouldPropagateHeadersWithBranchPrefixWhenExchangeHeaderAlreadyProcessed() {
        Exchange oldExchange = createBranchExchange("mainBranch", "{}");
        Exchange newExchange = createBranchExchange("warmup", "{}");
        Exchange inputExchange = defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        oldExchange.getMessage().setHeader("X-Main-Id", "main-1");

        newExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "secondary");
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_HEADERS, true);
        newExchange.getMessage().setHeader("X-Secondary-Id", "secondary-1");
        newExchange.setProperty(Properties.SPLIT_EXCHANGE_HEADER_PROCESSED, true);

        inputExchange.setProperty(Properties.SPLIT_EXCHANGE_HEADER_PROCESSED, true);

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals("main-1", result.getMessage().getHeader("X-Main-Id"));
        assertEquals("secondary-1", result.getMessage().getHeader("warmup.X-Secondary-Id"));
        assertNull(result.getMessage().getHeader("X-Secondary-Id"));
    }

    @Test
    void shouldPropagateHeadersWithBranchPrefixWhenBranchSharesInputHeaderMap() {
        Exchange oldExchange = createBranchExchange("mainBranch", "{}");
        Exchange newExchange = createBranchExchange("warmup", "{}");
        Exchange inputExchange = defaultExchange();

        Map<String, Object> sharedHeaders = inputExchange.getMessage().getHeaders();
        oldExchange.getMessage().setHeaders(sharedHeaders);
        newExchange.getMessage().setHeaders(sharedHeaders);

        oldExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        newExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "secondary");
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_HEADERS, true);
        newExchange.getMessage().setHeader("X-Secondary-Id", "secondary-1");

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals("secondary-1", result.getMessage().getHeader("warmup.X-Secondary-Id"));
    }

    @Test
    void shouldReturnNullWhenFirstExchangeIsRollbackOnly() {
        Exchange newExchange = createBranchExchange("customerDetails", "{}");
        Exchange inputExchange = defaultExchange();
        newExchange.setRollbackOnly(true);

        assertNull(strategy.aggregate(null, newExchange, inputExchange));
    }

    @Test
    void shouldReturnOldExchangeWhenNextExchangeIsRollbackOnly() {
        Exchange oldExchange = createBranchExchange("customerDetails", "{}");
        Exchange newExchange = createBranchExchange("orderSummary", "{}");
        Exchange inputExchange = defaultExchange();
        newExchange.setRollbackOnly(true);

        assertSame(oldExchange, strategy.aggregate(oldExchange, newExchange, inputExchange));
    }

    @Test
    void shouldReturnOldExchangeWhenErrorHandlerHandledSet() {
        Exchange oldExchange = createBranchExchange("customerDetails", "{}");
        Exchange newExchange = mock(Exchange.class);
        Exchange inputExchange = defaultExchange();
        ExchangeExtension extension = mock(ExchangeExtension.class);

        when(newExchange.isFailed()).thenReturn(false);
        when(newExchange.isRollbackOnly()).thenReturn(false);
        when(newExchange.isRollbackOnlyLast()).thenReturn(false);
        when(newExchange.getExchangeExtension()).thenReturn(extension);
        when(extension.isErrorHandlerHandledSet()).thenReturn(true);
        when(extension.isErrorHandlerHandled()).thenReturn(true);

        assertSame(oldExchange, strategy.aggregate(oldExchange, newExchange, inputExchange));
    }

    @Test
    void shouldAggregateFirstSuccessfulExchangeWithInputExchange() {
        Exchange newExchange = createBranchExchange("customerDetails", "{\"customerId\":\"C-1\"}");
        Exchange inputExchange = defaultExchange();
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        newExchange.setProperty("customerId", "C-1");

        Exchange result = strategy.aggregate(null, newExchange, inputExchange);

        JsonNode body = (JsonNode) result.getMessage().getBody();
        assertEquals("C-1", body.get("customerDetails").get("customerId").asText());
        assertEquals("C-1", result.getProperty("customerDetails.customerId"));
        assertEquals(true, inputExchange.getProperty(Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED, Boolean.class));
    }

    @Test
    void shouldNotPropagateSecondaryHeadersWhenPropagationDisabled() {
        Exchange oldExchange = createBranchExchange("mainBranch", "{}");
        Exchange newExchange = createBranchExchange("warmup", "{}");
        Exchange inputExchange = defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        newExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "secondary");
        newExchange.getMessage().setHeader("X-Secondary-Id", "secondary-1");

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertNull(result.getMessage().getHeader("warmup.X-Secondary-Id"));
        assertNull(result.getMessage().getHeader("X-Secondary-Id"));
    }

    @Test
    void shouldNotPropagateSecondaryPropertiesWhenPropagationDisabled() {
        Exchange oldExchange = createBranchExchange("mainBranch", "{}");
        Exchange newExchange = createBranchExchange("warmup", "{}");
        Exchange inputExchange = defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        newExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "secondary");
        newExchange.setProperty("testProp", "from-secondary");

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertNull(result.getProperty("warmup.testProp"));
        assertNull(result.getProperty("testProp"));
    }

    @Test
    void shouldPropagateMainBranchPropertiesWithoutPrefix() {
        Exchange oldExchange = createBranchExchange("mainBranch", "{}");
        Exchange newExchange = createBranchExchange("warmup", "{}");
        Exchange inputExchange = defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        oldExchange.setProperty("mainProp", "from-main");
        newExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "secondary");
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        newExchange.setProperty("secondaryProp", "from-secondary");

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals("from-main", result.getProperty("mainProp"));
        assertEquals("from-secondary", result.getProperty("warmup.secondaryProp"));
    }

    @Test
    void shouldRemoveInputHeadersAbsentOnBranchDuringSynchronization() {
        Exchange oldExchange = createBranchExchange("customerDetails", "{}");
        Exchange newExchange = createBranchExchange("orderSummary", "{}");
        Exchange inputExchange = defaultExchange();

        inputExchange.getMessage().setHeader("stale-header", "stale-value");
        oldExchange.getMessage().setHeader("X-Customer-Id", "C-1");
        oldExchange.setProperty(Properties.SPLIT_PROPAGATE_HEADERS, true);
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_HEADERS, true);
        newExchange.getMessage().setHeader("X-Order-Id", "O-1");

        strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertNull(inputExchange.getMessage().getHeader("stale-header"));
        assertEquals("C-1", inputExchange.getMessage().getHeader("customerDetails.X-Customer-Id"));
        assertNull(inputExchange.getMessage().getHeader("X-Customer-Id"));
    }

    @Test
    void shouldRemoveInputPropertiesAbsentOnBranchDuringSynchronization() {
        Exchange oldExchange = createBranchExchange("customerDetails", "{}");
        Exchange newExchange = createBranchExchange("orderSummary", "{}");
        Exchange inputExchange = defaultExchange();

        inputExchange.setProperty("staleProp", "stale-value");
        oldExchange.setProperty("customerId", "C-1");
        oldExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        newExchange.setProperty("orderId", "O-1");

        strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertNull(inputExchange.getProperty("staleProp"));
        assertEquals("C-1", inputExchange.getProperty("customerDetails.customerId"));
    }

    @Test
    void shouldSkipInternalPropertyPrefixWhenPropagatingProperties() {
        Exchange oldExchange = createBranchExchange("warmup", "{}");
        Exchange newExchange = createBranchExchange("orderSummary", "{}");
        Exchange inputExchange = defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        oldExchange.setProperty(Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED, true);
        oldExchange.setProperty("userProp", "user-value");

        strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals("user-value", inputExchange.getProperty("warmup.userProp"));
        assertNull(inputExchange.getProperty("warmup." + Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED));
    }

    @Test
    void shouldNotPropagateInternalPropertyPrefixKeysFromSecondaryBranch() {
        Exchange oldExchange = createBranchExchange("mainBranch", "{}");
        Exchange newExchange = createBranchExchange("warmup", "{}");
        Exchange inputExchange = defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        newExchange.setProperty(Properties.SPLIT_BRANCH_TYPE, "secondary");
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        newExchange.setProperty("testProp", "from-secondary");
        newExchange.setProperty(Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED, "already-processed");

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals("from-secondary", result.getProperty("warmup.testProp"));
        assertNull(result.getProperty("warmup." + Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED));
    }

    @Test
    void shouldPreservePropagationFlagsOnAggregatedExchange() {
        Exchange oldExchange = createBranchExchange("warmup", "{}");
        Exchange newExchange = createBranchExchange("orderSummary", "{}");
        Exchange inputExchange = defaultExchange();

        oldExchange.setProperty(Properties.SPLIT_PROPAGATE_HEADERS, true);
        oldExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_HEADERS, false);
        newExchange.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, false);

        Exchange result = strategy.aggregate(oldExchange, newExchange, inputExchange);

        assertEquals(true, result.getProperty(Properties.SPLIT_PROPAGATE_HEADERS, Boolean.class));
        assertEquals(true, result.getProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, Boolean.class));
        assertEquals(true, result.getProperty(Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED, Boolean.class));
    }

    @Test
    void shouldClearSplitIdChainWhenAdditionalBranchAggregated() {
        Exchange aggregatedExchange = createBranchExchange("customerDetails", "{\"customerId\":\"C-1\"}");
        Exchange newExchange = createBranchExchange("orderSummary", "{\"orderId\":\"O-1\"}");
        aggregatedExchange.setProperty(Properties.SPLIT_ID_CHAIN, "parent-chain");

        Exchange result = strategy.aggregate(aggregatedExchange, newExchange);

        assertNull(result.getProperty(Properties.SPLIT_ID));
        assertNull(result.getProperty(Properties.SPLIT_ID_CHAIN));
    }

    @Test
    void shouldReturnOldExchangeWithoutUpdatingBodyWhenOldBodyIsInvalidJson() {
        Exchange aggregatedExchange = createBranchExchange("customerDetails", "{\"customerId\":\"C-1\"}");
        Exchange newExchange = createBranchExchange("orderSummary", "{\"orderId\":\"O-1\"}");
        aggregatedExchange.getMessage().setBody("{invalid-json");

        Exchange result = strategy.aggregate(aggregatedExchange, newExchange);

        assertSame(aggregatedExchange, result);
        assertEquals("{invalid-json", result.getMessage().getBody(String.class));
    }

    @Test
    void shouldNotCreateNullPrefixedHeadersWhenSecondaryBranchHasNoSplitId() {
        Exchange mergedSecondary = createBranchExchange("warmup", "{}");
        mergedSecondary.setProperty(Properties.SPLIT_BRANCH_TYPE, "secondary");
        mergedSecondary.setProperty(Properties.SPLIT_PROPAGATE_HEADERS, true);
        mergedSecondary.getMessage().setHeader("X-Secondary-Id", "secondary-1");
        mergedSecondary.removeProperty(Properties.SPLIT_ID);

        Exchange mainBranch = createBranchExchange("mainBranch", "{}");
        mainBranch.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        Exchange inputExchange = defaultExchange();

        Exchange result = strategy.aggregate(mergedSecondary, mainBranch, inputExchange);

        assertNull(result.getMessage().getHeader("null.X-Secondary-Id"));
    }

    @Test
    void shouldNotCreateNullPrefixedPropertiesWhenSecondaryBranchHasNoSplitId() {
        Exchange mergedSecondary = createBranchExchange("warmup", "{}");
        mergedSecondary.setProperty(Properties.SPLIT_BRANCH_TYPE, "secondary");
        mergedSecondary.setProperty(Properties.SPLIT_PROPAGATE_PROPERTIES, true);
        mergedSecondary.setProperty("testProp", "from-secondary");
        mergedSecondary.removeProperty(Properties.SPLIT_ID);

        Exchange mainBranch = createBranchExchange("mainBranch", "{}");
        mainBranch.setProperty(Properties.SPLIT_BRANCH_TYPE, "main");
        Exchange inputExchange = defaultExchange();

        Exchange result = strategy.aggregate(mergedSecondary, mainBranch, inputExchange);

        assertNull(result.getProperty("null.testProp"));
    }

    private Exchange defaultExchange() {
        return new DefaultExchange(new DefaultCamelContext());
    }

    private Exchange createBranchExchange(String branchName, String body) {
        Exchange exchange = defaultExchange();
        exchange.setProperty(Properties.SPLIT_ID, branchName);
        exchange.getMessage().setBody(body);
        return exchange;
    }
}
