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

import org.qubership.integration.platform.engine.camel.CorrelationIdSetter;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.MessageHelper;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Processor is used to parse path variables from uri of input http request
 * and set them as environment variables at camel context
 */
@Slf4j
@Component
public class HttpTriggerProcessor implements Processor {

    private static final Pattern URI_REGEXP = Pattern.compile("(\\/?\\{?[^\\/]*}?\\/?)");
    private static final Pattern VARIABLE_REGEXP = Pattern.compile("^\\/?\\{[^\\/]*}\\/?$");
    private static final String RESPONSE_FILTER_EXCLUDE_QUERY_PARAM = "excludeFields";
    private static final String RESPONSE_FILTER_INCLUDE_QUERY_PARAM = "fields";

    private final CorrelationIdSetter correlationIdSetter;

    private final JsonMessageValidator validator;

    @Autowired
    public HttpTriggerProcessor(CorrelationIdSetter correlationIdSetter, JsonMessageValidator validator) {
        this.correlationIdSetter = correlationIdSetter;
        this.validator = validator;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        saveUriContextForLogging(exchange);
        validate(exchange);
        // skip path variables parsing in case of checkpoint retry
        if (!exchange.getProperty(Properties.IS_CHECKPOINT_TRIGGER_STEP, false, Boolean.class)) {
            parsePathVariables(exchange);
        }
        parseResponseFilterParameters(exchange);
        removeHeaders(exchange);
    }

    private void saveUriContextForLogging(Exchange exchange) {
        String actualUrl = getHeader(exchange, Exchange.HTTP_URL);
        exchange.setProperty(Properties.SERVLET_REQUEST_URL, actualUrl);
        String stepId = DebuggerUtils.getStepChainElementId(exchange.getAllProperties().get(Exchange.STEP_ID).toString());
        exchange.setProperty(Properties.HTTP_TRIGGER_STEP_ID, stepId);
    }

    private void parsePathVariables(Exchange exchange) {
        var uriTemplate = "routes/" + getHeader(exchange, Headers.URI_TEMPLATE);
        var actualUri = getHeader(exchange, Exchange.HTTP_URI);

        var templateMatcher = URI_REGEXP.matcher(uriTemplate);
        var valuesMatcher = URI_REGEXP.matcher(actualUri);

        while (templateMatcher.find()) {
            var name = templateMatcher.group();

            if (StringUtils.isNotBlank(name)) {
                var matcher = VARIABLE_REGEXP.matcher(name);
                var isParam = matcher.find();
                var foundValue = valuesMatcher.find();

                if (isParam && foundValue) {
                    var value = valuesMatcher.group();

                    if (StringUtils.isNotBlank(value)) {
                        var variableName = removeServiceSymbols(name);
                        var variableValue = removeServiceSymbols(value);

                        exchange.setProperty(variableName, variableValue);
                    }
                }
            }
        }

        correlationIdSetter.setCorrelationId(exchange);
    }

    private String getHeader(Exchange exchange, String headerName) {
        var message = exchange.getMessage();
        var headers = message.getHeaders();
        var header = (String) headers.get(headerName);
        return header == null ? StringUtils.EMPTY : header;
    }

    private String removeServiceSymbols(String str) {
        return str.replace("}", "")
                .replace("{", "")
                .replace("/", "");
    }

    private void removeHeaders(Exchange exchange) {
        var message = exchange.getMessage();
        message.removeHeader(Exchange.HTTP_URI);
        message.removeHeader(Exchange.HTTP_URL);
        message.removeHeader(Exchange.HTTP_PATH);
    }

    private void validate(Exchange exchange) throws IOException {
        validateBodyWithGetDelete(exchange);
        validateContentType(exchange);
        validateJSON(exchange);
    }

    private void validateContentType(Exchange exchange) {
        String[] allowedContentTypes =
            exchange.getProperty(Properties.ALLOWED_CONTENT_TYPES_PROP, String[].class);
        if (allowedContentTypes != null && allowedContentTypes.length > 0) {
            MimeType messageMimeType;
            try {
                messageMimeType = PayloadExtractor.extractContentType(exchange);
            } catch (Exception e) {
                throw new ValidationException(
                    "Unsupported content type: '" + exchange.getMessage().getHeaders().getOrDefault(
                        HttpHeaders.CONTENT_TYPE, "") + "'");
            }

            for (String allowedType : allowedContentTypes) {
                MimeType allowedMimeType;
                try {
                    allowedMimeType = MimeType.valueOf(allowedType);
                } catch (Exception e) {
                    throw new RuntimeException(
                        "Unsupported content type found in validation list: '" + allowedType + "', please fix it");
                }
                if (messageMimeType != null && messageMimeType.equalsTypeAndSubtype(allowedMimeType)) {
                    return;
                }
            }

            throw new ValidationException(
                "Unsupported content type: '" + messageMimeType + "'");
        }
    }

    private void validateBodyWithGetDelete(Exchange exchange) throws IOException {
        // property is true by default, but for compatibility this is set to false here
        boolean rejectRequestIfBodyNullGetDelete = exchange.getProperty(
            Properties.REJECT_REQUEST_IF_NULL_BODY_GET_DELETE_PROP, false, Boolean.class);
        if (rejectRequestIfBodyNullGetDelete) {
            String method = exchange.getMessage().getHeader(Exchange.HTTP_METHOD, String.class);
            if (StringUtils.isNotEmpty(MessageHelper.extractBody(exchange)) &&
                ("GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))
            ) {
                throw new ValidationException(
                    "Not empty body is not allowed with [" + method + "] method, request rejected");
            }
        }
    }

    private void validateJSON(Exchange exchange) throws IOException {
        String validationSchema = exchange.getProperty(Properties.VALIDATION_SCHEMA, String.class);
        if (!StringUtils.isBlank(validationSchema)) {
            String inputJsonMessage = MessageHelper.extractBody(exchange);
            validator.validate(inputJsonMessage, validationSchema);
        }
    }

    private void parseResponseFilterParameters(Exchange exchange) {
        Boolean responseFilter = exchange.getProperty(Properties.RESPONSE_FILTER, Boolean.class);
        String queryString = exchange.getMessage().getHeader(Exchange.HTTP_QUERY, String.class);
        if (responseFilter != null && responseFilter && StringUtils.isNotBlank(queryString)) {
            List<NameValuePair> queryParams = URLEncodedUtils.parse(queryString, StandardCharsets.UTF_8);

            String excludeFilterValue = queryParams.stream().filter(p -> RESPONSE_FILTER_EXCLUDE_QUERY_PARAM.equals(p.getName()))
                    .map(NameValuePair::getValue).findAny().orElse(null);
            if (StringUtils.isNotBlank(excludeFilterValue)) {
                exchange.setProperty(Properties.RESPONSE_FILTER_EXCLUDE_FIELDS, excludeFilterValue);
            }

            String includeFilterValue = queryParams.stream().filter(p -> RESPONSE_FILTER_INCLUDE_QUERY_PARAM.equals(p.getName()))
                    .map(NameValuePair::getValue).findAny().orElse(null);
            if (StringUtils.isNotBlank(includeFilterValue)) {
                exchange.setProperty(Properties.RESPONSE_FILTER_INCLUDE_FIELDS, includeFilterValue);
            }
        }
    }
}
