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

package org.qubership.integration.platform.engine.service.debugger.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.support.http.HttpUtil;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.qubership.integration.platform.engine.errorhandling.LoggingMaskingException;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.service.debugger.masking.MaskingService;
import org.qubership.integration.platform.engine.util.ExchangeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PayloadExtractor {
    private final MaskingService maskingService;
    private final ObjectMapper objectMapper;
    private final Optional<CamelExchangeContextPropagation> exchangeContextPropagation;

    @Autowired
    public PayloadExtractor(MaskingService maskingService, @Qualifier("jsonMapper") ObjectMapper objectMapper,
        Optional<CamelExchangeContextPropagation> exchangeContextPropagation) {
        this.maskingService = maskingService;
        this.objectMapper = objectMapper;
        this.exchangeContextPropagation = exchangeContextPropagation;
    }

    public Map<String, String> extractHeadersForLogging(Exchange exchange, Set<String> maskedFields, boolean maskingEnabled) {
        Map<String, String> headers = exchange.getMessage().getHeaders().entrySet().stream().collect(
            Collectors.toMap(
                Entry::getKey, entry -> entry.getValue() != null ? entry.getValue().toString() : ""));
        if (maskingEnabled) {
            maskingService.maskFields(headers, maskedFields);
        }
        return headers;
    }

    /**
     * Extract body for logging from exchange
     *
     * @param exchange
     * @param maskedFields
     * @return body as is, masked body
     */
    public String extractBodyForLogging(Exchange exchange, Set<String> maskedFields, boolean maskingEnabled) {
        String maskedBody = MessageHelper.extractBody(exchange);
        MimeType contentType = extractContentType(exchange);

        if (maskingEnabled && !maskedFields.isEmpty() && StringUtils.isNotEmpty(maskedBody) && contentType != null) {
            try {
                maskedBody = maskingService.maskFields(maskedBody, maskedFields, contentType);
            } catch (LoggingMaskingException | UnsupportedMediaTypeException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Failed to mask fields in body");
                }
            }
        }

        return maskedBody;
    }

    public Map<String, SessionElementProperty> extractExchangePropertiesForLogging(Exchange exchange, Set<String> maskedFields, boolean maskingEnabled) {
        Map<String, SessionElementProperty> properties = ExchangeUtils.prepareExchangePropertiesForLogging(exchange);
        if (maskingEnabled) {
            maskingService.maskPropertiesFields(properties, maskedFields);
        }
        for (Map.Entry<String, SessionElementProperty> entry : properties.entrySet()) {
            String key = entry.getKey();
            SessionElementProperty value = entry.getValue();

            try {
                if (maskingEnabled && !maskedFields.isEmpty()) {
                    value.setValue(maskingService.maskJSON(value.getValue(), maskedFields));
                }
                properties.put(key, value);
            } catch (JsonProcessingException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Property " + key + " has invalid json");
                }
            }
        }
        return properties;
    }

    public Map<String, String> extractContextForLogging(Set<String> maskedFields, boolean maskingEnabled) {

        Map<String, String> headers = exchangeContextPropagation.isPresent()
                ? exchangeContextPropagation.get().buildContextSnapshotForSessions()
                : new HashMap<>();
        if (maskingEnabled) {
            maskingService.maskFields(headers, maskedFields);
        }
        return headers;
    }

    public String convertToJson(Map<String, ?> mapData) {
        if (mapData == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(mapData);
        } catch (JsonProcessingException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error while logging in json processing {}", e.getMessage());
            }
        }
        return null;
    }

    public static Integer getResponseCode(Map<String, Object> headers) {
        Object responseCodeObj = headers.get(Headers.CAMEL_HTTP_RESPONSE_CODE);
        return responseCodeObj == null ? null : Integer.valueOf(responseCodeObj.toString());
    }

    public static int getServletResponseCode(Exchange exchange, Exception exception) {
        if (exception != null) {
            return ErrorCode.match(exception).getHttpErrorCode();
        }
        return HttpUtil.determineResponseCode(exchange, exchange.getMessage().getBody());
    }

    public static MimeType extractContentType(Exchange exchange) {
        Object contentType = exchange.getMessage().getHeaders().getOrDefault(
                HttpHeaders.CONTENT_TYPE, null);
        return contentType == null ? null : MimeType.valueOf(String.valueOf(contentType));
    }
}
