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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.util.MDCUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class CorrelationIdSetter {

    public static final String CORRELATION_ID_POSITION = "correlationIdPosition";
    public static final String CORRELATION_ID_NAME = "correlationIdName";
    public static final String CORRELATION_ID = "correlationId";
    public static final String HEADER = "Header";
    public static final String BODY = "Body";

    private final ObjectMapper objectMapper;

    @Autowired
    public CorrelationIdSetter(@Qualifier("jsonMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void setCorrelationId(Exchange exchange) {
        if (exchange.getProperty(CORRELATION_ID_POSITION) != null && exchange.getProperty(CORRELATION_ID_NAME) != null) {
            String correlationIdPosition = String.valueOf(exchange.getProperty(CORRELATION_ID_POSITION));
            String correlationIdName = String.valueOf(exchange.getProperty(CORRELATION_ID_NAME));
            if (HEADER.equals(correlationIdPosition)) {
                if (exchange.getMessage().getHeader(correlationIdName) != null) {
                    String correlationId = String.valueOf(exchange.getMessage().getHeader(correlationIdName));
                    exchange.setProperty(CORRELATION_ID, correlationId);
                    if (StringUtils.isNotBlank(correlationId)) {
                        MDCUtil.setCorrelationId(correlationId);
                    }
                } else {
                    exchange.setProperty(CORRELATION_ID, null);
                }
            } else if (BODY.equals(correlationIdPosition)) {
                try {
                    Map<String, Object> body = objectMapper.readValue(MessageHelper.extractBody(exchange), HashMap.class);
                    if (body.containsKey(correlationIdName)) {
                        String correlationId = String.valueOf(body.get(correlationIdName));
                        exchange.setProperty(CORRELATION_ID, correlationId);
                        if (StringUtils.isNotBlank(correlationId)) {
                            MDCUtil.setCorrelationId(correlationId);
                        }
                    }
                } catch (IOException e) {
                    log.error("Error while finding correlation id with name {}", correlationIdName);
                }
            }
        }
    }
}
