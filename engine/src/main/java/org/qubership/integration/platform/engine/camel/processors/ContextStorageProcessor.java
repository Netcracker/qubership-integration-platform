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

package org.qubership.integration.platform.engine.camel.processors;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.service.contextstorage.ContextStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.CORRELATION_ID;


@Slf4j
@Component
public class ContextStorageProcessor implements Processor {

    enum Operation {
        GET,
        SET,
        DELETE
    }

    enum Target {
        HEADER,
        PROPERTY,
        BODY
    }

    private static final String SESSION_CONTEXT_PROPERTY_PREFIX = CamelConstants.INTERNAL_PROPERTY_PREFIX + "contextStorage_";
    private static final String PROPERTY_USE_CORRELATION_ID = SESSION_CONTEXT_PROPERTY_PREFIX + "useCorrelationId";
    private static final String PROPERTY_CONTEXT_ID = SESSION_CONTEXT_PROPERTY_PREFIX + "contextId";
    private static final String PROPERTY_CONTEXT_SERVICE_ID = SESSION_CONTEXT_PROPERTY_PREFIX + "contextServiceId";
    private static final String PROPERTY_OPERATION = SESSION_CONTEXT_PROPERTY_PREFIX + "operation";
    private static final String PROPERTY_KEY = SESSION_CONTEXT_PROPERTY_PREFIX + "key";
    private static final String PROPERTY_VALUE = SESSION_CONTEXT_PROPERTY_PREFIX + "value";
    private static final String PROPERTY_TTL = SESSION_CONTEXT_PROPERTY_PREFIX + "ttl";
    private static final String PROPERTY_KEYS = SESSION_CONTEXT_PROPERTY_PREFIX + "keys";
    private static final String PROPERTY_TARGET = SESSION_CONTEXT_PROPERTY_PREFIX + "target";
    private static final String PROPERTY_TARGET_NAME = SESSION_CONTEXT_PROPERTY_PREFIX + "targetName";
    private static final String PROPERTY_UNWRAP = SESSION_CONTEXT_PROPERTY_PREFIX + "unwrap";
    private final ContextStorageService contextStorageService;


    @Autowired
    public ContextStorageProcessor(
            ContextStorageService contextStorageService
    ) {
        this.contextStorageService = contextStorageService;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String sessionId = getContextSessionId(exchange);
        Operation operation = readEnumValue(exchange, PROPERTY_OPERATION, Operation.class);
        switch (operation) {
            case GET -> processGetValue(exchange, sessionId);
            case SET -> processSetValue(exchange, sessionId);
            case DELETE -> deleteContext(exchange, sessionId);
        }
    }

    private void processGetValue(Exchange exchange, String sessionId) throws Exception {
        String contextServiceId = exchange.getProperty(PROPERTY_CONTEXT_SERVICE_ID, String.class);
        String contextId = Optional.ofNullable(exchange.getProperty(PROPERTY_CONTEXT_ID, String.class)).orElse(sessionId);
        List<String> contextKey = Optional.ofNullable(exchange.getProperty(PROPERTY_KEYS, String.class))
                .map(value -> List.of(value.split(",")))
                .orElse(List.of());
        Target target = readEnumValue(exchange, PROPERTY_TARGET, Target.class);
        String name = exchange.getProperty(PROPERTY_TARGET_NAME, String.class);
        boolean unwrap = (!exchange.getProperty(PROPERTY_UNWRAP).toString().isEmpty())
                ? exchange.getProperty(PROPERTY_UNWRAP, Boolean.class)
                : false;
        Map<String, String> map = contextStorageService.getValue(contextServiceId, contextId, contextKey);
        switch (target) {
            case BODY -> exchange.getMessage().setBody(unwrap ? map.values().stream().findFirst().orElse(null) : map);
            case HEADER -> exchange.getMessage().setHeader(name, unwrap ? map.values().stream().findFirst().orElse(null) : map);
            case PROPERTY -> exchange.setProperty(name, unwrap ? map.values().stream().findFirst().orElse(null) : map);
        }
    }

    private void processSetValue(Exchange exchange, String sessionId) throws Exception {
        String contextKey = exchange.getProperty(PROPERTY_KEY, String.class);
        String contextValue = exchange.getProperty(PROPERTY_VALUE, String.class);
        String contextServiceId = exchange.getProperty(PROPERTY_CONTEXT_SERVICE_ID, String.class);
        String contextId = Optional.ofNullable(exchange.getProperty(PROPERTY_CONTEXT_ID, String.class)).orElse(sessionId);
        long ttl = exchange.getProperty(PROPERTY_TTL, Long.class);
        contextStorageService.storeValue(contextKey, contextValue, contextServiceId, contextId, ttl);
    }

    private void deleteContext(Exchange exchange, String sessionId) throws Exception {
        String contextServiceId = exchange.getProperty(PROPERTY_CONTEXT_SERVICE_ID, String.class);
        String contextId = Optional.ofNullable(exchange.getProperty(PROPERTY_CONTEXT_ID, String.class)).orElse(sessionId);
        contextStorageService.deleteValue(contextServiceId, contextId);
    }

    private <T extends Enum<T>> T readEnumValue(Exchange exchange, String propertyName, Class<T> cls) {
        return Optional.ofNullable(exchange.getProperty(propertyName, String.class))
                .map(value -> Enum.valueOf(cls, value.toUpperCase()))
                .orElse(null);
    }

    private String getContextSessionId(Exchange exchange) {
        boolean useCorrelationId = exchange.getProperty(PROPERTY_USE_CORRELATION_ID, Boolean.class);
        return useCorrelationId
                ? exchange.getProperty(CORRELATION_ID, String.class)
                : exchange.getProperty(PROPERTY_CONTEXT_ID, String.class);
    }




}
