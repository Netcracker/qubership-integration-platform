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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeExtension;
import org.apache.camel.Message;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.engine.util.ExchangeUtils.isCommonOrSystemVariableMap;

@Slf4j
@Component
public class ChainsAggregationStrategy implements AggregationStrategy {
    private static final String SPLIT_MAIN_BRANCH_TYPE = "main";
    private static final String CAMEL_HTTP_RESPONSE_CODE_VALUE = "200";
    private final ObjectMapper objectMapper;

    @Autowired
    public ChainsAggregationStrategy(@Qualifier("jsonMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange, Exchange inputExchange) {
        if (oldExchange == null) {
            if (hadException(newExchange)) {
                return null;
            }
            processHeaders(newExchange, (Exchange) null, inputExchange);
            processProperties(newExchange, (Exchange) null, inputExchange);
            return aggregate(oldExchange, newExchange);
        }
        if (hadException(newExchange)) {
            return oldExchange;
        }
        processHeaders(oldExchange, newExchange, inputExchange);
        processProperties(oldExchange, newExchange, inputExchange);
        return aggregate(oldExchange, newExchange);
    }

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        if (oldExchange == null) {
            ObjectNode newBodyWrapped = objectMapper.createObjectNode();
            processPayload(newExchange, newExchange, newBodyWrapped);
            return newExchange;
        }
        try {
            ObjectNode oldBody = (ObjectNode) objectMapper.readTree(oldExchange.getMessage().getBody(String.class));
            processPayload(oldExchange, newExchange, oldBody);
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
        clearBranchName(oldExchange);
        return oldExchange;
    }

    private void processHeaders(Exchange oldExchange, Exchange newExchange, Exchange inputExchange) {
        Map<String, Object> headers = inputExchange.getMessage().getHeaders();
        processHeaders(oldExchange, headers, inputExchange);
        processHeaders(newExchange, headers, inputExchange);
        Message message = oldExchange.getMessage();
        message.setHeaders(headers);
    }

    private void processHeaders(Exchange exchange, Map<String, Object> headers, Exchange inputExchange) {
        if (exchange != null) {
            if (!exchangeHeaderProcessed(inputExchange)) {
                List<String> keysToRemove = new ArrayList<>();
                Map<String, Object> inputHeaders = inputExchange.getMessage().getHeaders();
                inputHeaders.forEach((key, value) -> {
                    if (exchange.getMessage().getHeaders().containsKey(key)) {
                        headers.put(key, value);
                    } else {
                        keysToRemove.add(key);
                    }
                });
                keysToRemove.forEach(inputHeaders::remove);
            }

            inputExchange.setProperty(CamelConstants.Properties.SPLIT_EXCHANGE_HEADER_PROCESSED, true);
            if (isHeadersPropagationEnabled(exchange) || isMainBranch(exchange)) {
                String branchName = getBranchName(exchange);
                exchange.getMessage().getHeaders().forEach((key, value) -> {
                    if (!CamelConstants.isInternalHeader(key)) {
                        if (!exchangeHeaderProcessed(exchange) && !isMainBranch(exchange)) {
                            key = String.format("%s.%s", branchName, key);
                        }
                        headers.put(key, value);
                    }
                });
                exchange.setProperty(CamelConstants.Properties.SPLIT_EXCHANGE_HEADER_PROCESSED, true);
            }
        }
    }

    private void processProperties(Exchange oldExchange, Exchange newExchange, Exchange inputExchange) {
        Map<String, Object> properties = inputExchange.getProperties();
        processProperties(oldExchange, properties, inputExchange);
        String branchName = (String) oldExchange.getProperty(CamelConstants.Properties.SPLIT_ID);
        Boolean headerProcessed = exchangeHeaderProcessed(oldExchange);
        Boolean headerPropagationEnabled =
            isMainBranch(oldExchange) || isHeadersPropagationEnabled(oldExchange);
        Boolean propertiesPropagationEnabled =
            isMainBranch(oldExchange) || isPropertiesPropagationEnabled(oldExchange);
        oldExchange.removeProperties(".*");
        oldExchange.setProperty(CamelConstants.Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED, true);
        oldExchange.setProperty(CamelConstants.Properties.SPLIT_EXCHANGE_HEADER_PROCESSED, headerProcessed);
        oldExchange.setProperty(CamelConstants.Properties.SPLIT_ID, branchName);
        oldExchange.setProperty(CamelConstants.Properties.SPLIT_PROPAGATE_HEADERS, headerPropagationEnabled);
        oldExchange.setProperty(CamelConstants.Properties.SPLIT_PROPAGATE_PROPERTIES, propertiesPropagationEnabled);
        processProperties(newExchange, properties, inputExchange);
        properties.forEach(oldExchange::setProperty);
    }

    private void processProperties(Exchange exchange, Map<String, Object> properties, Exchange inputExchange) {
        if (exchange != null) {
            if (!exchangePropertiesProcessed(inputExchange)) {
                List<String> propertiesToRemove = new ArrayList<>();
                Map<String, Object> inputProperties = inputExchange.getProperties();
                inputProperties.forEach((key, value) -> {
                    if (exchange.getProperties().containsKey(key)) {
                        properties.put(key, value);
                    } else {
                        propertiesToRemove.add(key);
                    }
                });
                propertiesToRemove.forEach(inputProperties::remove);
            }
            inputExchange.setProperty(CamelConstants.Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED, true);
            if (isPropertiesPropagationEnabled(exchange) || isMainBranch(exchange)) {
                String branchName = getBranchName(exchange);
                exchange.getProperties().forEach((key, value) -> {
                    if (!(isCommonOrSystemVariableMap(key) || CamelConstants.isInternalProperty(key))) {
                        if (!exchangePropertiesProcessed(exchange) && !isMainBranch(exchange)) {
                            key = String.format("%s.%s", branchName, key);
                        }
                        properties.put(key, value);
                    }
                });
            }
        }
    }

    private String getBranchName(Exchange exchange) {
        return exchange.getProperty(CamelConstants.Properties.SPLIT_ID, String.class);
    }

    private void clearBranchName(Exchange exchange) {
        exchange.removeProperty(CamelConstants.Properties.SPLIT_ID);
        exchange.removeProperty(CamelConstants.Properties.SPLIT_ID_CHAIN);
    }

    private void processPayload(Exchange oldExchange, Exchange newExchange, ObjectNode oldBody) {
        String rawExchange = newExchange.getMessage().getBody(String.class);
        try {
            JsonNode newBody = objectMapper.readTree(rawExchange);
            oldBody.replace(getBranchName(newExchange), objectMapper.readTree(objectMapper.writeValueAsString(newBody)));
        } catch (JsonProcessingException | ClassCastException | IllegalArgumentException e) {
            oldBody.replace(getBranchName(newExchange), new TextNode(rawExchange));
        }
        oldExchange.getIn().setBody(oldBody);
        oldExchange.getIn().setHeader(Headers.CAMEL_HTTP_RESPONSE_CODE, CAMEL_HTTP_RESPONSE_CODE_VALUE);
        oldExchange.setProperty(CamelConstants.Properties.SPLIT_PROCESSED, true);
    }

    private boolean isHeadersPropagationEnabled(Exchange exchange) {
        return exchange.getProperty(
                CamelConstants.Properties.SPLIT_PROPAGATE_HEADERS, Boolean.FALSE, Boolean.class);
    }

    private boolean isPropertiesPropagationEnabled(Exchange exchange) {
        return exchange.getProperty(
                CamelConstants.Properties.SPLIT_PROPAGATE_PROPERTIES, Boolean.FALSE, Boolean.class);
    }

    private boolean exchangeHeaderProcessed(Exchange exchange) {
        return exchange.getProperty(
                CamelConstants.Properties.SPLIT_EXCHANGE_HEADER_PROCESSED, Boolean.FALSE, Boolean.class);
    }

    private boolean exchangeProcessed(Exchange exchange) {
        return exchange.getProperty(
                CamelConstants.Properties.SPLIT_PROCESSED, Boolean.FALSE, Boolean.class);
    }

    private boolean exchangePropertiesProcessed(Exchange exchange) {
        return exchange.getProperty(
                CamelConstants.Properties.SPLIT_EXCHANGE_PROPERTIES_PROCESSED, Boolean.FALSE, Boolean.class);
    }

    private boolean isMainBranch(Exchange exchange) {
        return exchange.getProperty(CamelConstants.Properties.SPLIT_BRANCH_TYPE, "", String.class)
                .equals(SPLIT_MAIN_BRANCH_TYPE);
    }

    private boolean hadException(Exchange exchange) {
        if (exchange.isFailed()) {
            return true;
        }

        if (exchange.isRollbackOnly()) {
            return true;
        }

        if (exchange.isRollbackOnlyLast()) {
            return true;
        }

        ExchangeExtension exchangeExtension = exchange.getExchangeExtension();
        return exchangeExtension.isErrorHandlerHandledSet() && exchangeExtension.isErrorHandlerHandled();
    }
}
