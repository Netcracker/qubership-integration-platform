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

package org.qubership.integration.platform.engine.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.service.debugger.util.json.JsonSerializationHelper;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

@Slf4j
public class ExchangeUtils {

    public static boolean isCommonOrSystemVariableMap(String propertyKey) {
        return Properties.VARIABLES_PROPERTY_MAP_NAME.equals(propertyKey);
    }

    public static void interruptExchange(Exchange exchange, int responseCode) {
        interruptExchange(exchange, responseCode, null);
    }

    public static void interruptExchange(Exchange exchange, int responseCode, Exception e) {
        exchange.getExchangeExtension().setInterrupted(true);
        Message message = exchange.getMessage();
        message.setBody(e == null ? "" : e.getMessage());
        message.removeHeaders("*");
        message.setHeader(Exchange.HTTP_RESPONSE_CODE, responseCode);
    }

    public static Map<String, SessionElementProperty> prepareExchangePropertiesForLogging(
        Exchange exchange) {
        Map<String, SessionElementProperty> exchangePropertiesForLogging = new HashMap<>();

        for (Map.Entry<String, Object> entry : exchange.getProperties().entrySet()) {
            String key = entry.getKey();
            boolean propertyShouldBeLogged = !(isCommonOrSystemVariableMap(key)
                || CamelConstants.isInternalProperty(key));
            if (propertyShouldBeLogged) {
                exchangePropertiesForLogging.put(key, serializePropertyValue(entry.getValue()));
            }
        }
        return exchangePropertiesForLogging;
    }

    private static SessionElementProperty serializePropertyValue(Object value) {
        if (value != null) {
            try {
                if (value instanceof String stringValue) {
                    return SessionElementProperty.builder()
                        .type(String.class.getName())
                        .value(stringValue)
                        .build();
                }

                return SessionElementProperty.builder()
                    .type(value.getClass().getName())
                    .value((value instanceof Iterable<?> || value instanceof Map<?, ?>) ?
                        value.toString() : JsonSerializationHelper.serializeJson(value))
                    .build();

            } catch (JsonProcessingException exception) {
                log.error(exception.getMessage());
            }
        }
        return SessionElementProperty.NULL_PROPERTY;
    }

    public static void setContentTypeIfMissing(Exchange exchange) {
        Message message = exchange.getMessage();
        Map<String, Object> headersMap = message.getHeaders();
        if (!headersMap.containsKey(CONTENT_TYPE)) {
            headersMap.put(CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        }
    }

    public static Map<String, Object> filterExchangeMap(Map<String, Object> map,
        Predicate<Entry<String, Object>> filterPredicate) {
        return map.entrySet().stream()
            .filter(filterPredicate)
            .filter(entry -> entry.getValue() != null)
            .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }
}
