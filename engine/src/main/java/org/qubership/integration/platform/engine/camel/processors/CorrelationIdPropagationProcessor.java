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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.qubership.integration.platform.engine.camel.CorrelationIdSetter.*;

@Slf4j
@Component
public class CorrelationIdPropagationProcessor implements Processor {

    private final ObjectMapper objectMapper;

    @Autowired
    public CorrelationIdPropagationProcessor(@Qualifier("jsonMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) {
        String correlationId = exchange.getProperty(CORRELATION_ID, String.class);
        if (correlationId != null && !correlationId.equals("null")) {
            String correlationIdPosition = exchange.getProperty(CORRELATION_ID_POSITION, String.class);
            String correlationIdName = exchange.getProperty(CORRELATION_ID_NAME, String.class);

            if (HEADER.equals(correlationIdPosition)) {
                exchange.getMessage().setHeader(correlationIdName, correlationId);
            } else if (BODY.equals(correlationIdPosition)) {
                try {
                    Map<String, Object> body = objectMapper.readValue(exchange.getMessage().getBody(String.class), HashMap.class);
                    body.put(correlationIdName, correlationId);
                    exchange.getMessage().setBody(objectMapper.writeValueAsString(body));
                } catch (JsonProcessingException e) {
                    log.error("Error while adding correlationId {} to body", correlationId);
                }
            }
        }
    }
}

