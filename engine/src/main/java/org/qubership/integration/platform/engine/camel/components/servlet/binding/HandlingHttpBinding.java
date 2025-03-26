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

package org.qubership.integration.platform.engine.camel.components.servlet.binding;

import com.arakelian.json.ImmutableJsonFilterOptions;
import com.arakelian.json.JsonFilter;
import com.arakelian.json.JsonReader;
import com.arakelian.json.JsonWriter;

import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomFilterStrategy;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.http.common.DefaultHttpBinding;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

@Component
@Slf4j
public class HandlingHttpBinding extends DefaultHttpBinding {
    @Autowired    
    public HandlingHttpBinding(ServletCustomFilterStrategy servletCustomFilterStrategy) {
        super();
        setHeaderFilterStrategy(servletCustomFilterStrategy);
    }

    @Override
    public void writeResponse(Exchange exchange, HttpServletResponse response) throws IOException {
        Message target = exchange.getMessage();
        if (exchange.isFailed()) {
            if (exchange.getException() != null) {
                doWriteExceptionResponse(exchange.getException(), response, exchange);
            } else {
                // it must be a fault, no need to check for the fault flag on the message
                doWriteFaultResponse(target, response, exchange);
            }
        } else {
            if (exchange.hasOut()) {
                // just copy the protocol relates header if we do not have them
                copyProtocolHeaders(exchange.getIn(), exchange.getOut());
            }
            doWriteResponse(target, response, exchange);
        }
    }

    private void copyProtocolHeaders(Message request, Message response) {
        if (request.getHeader(Exchange.CONTENT_ENCODING) != null) {
            String contentEncoding = request.getHeader(Exchange.CONTENT_ENCODING, String.class);
            response.setHeader(Exchange.CONTENT_ENCODING, contentEncoding);
        }
        if (checkChunked(response, response.getExchange())) {
            response.setHeader(Exchange.TRANSFER_ENCODING, "chunked");
        }
    }

    @Override
    public void doWriteExceptionResponse(Throwable exception, HttpServletResponse response) throws IOException {
        doWriteExceptionResponse(exception, response, null);
    }

    public void doWriteExceptionResponse(Throwable exception, HttpServletResponse response, Exchange exchange) throws IOException {
        if (exchange != null) {
            super.doWriteResponse(exchange.getMessage(), response, exchange);
            return;
        }

        sendInternalException(exception, response);
    }

    @Override
    public void doWriteResponse(Message message, HttpServletResponse response, Exchange exchange) throws IOException {
        if (!exchange.isFailed()) {
            filterFieldsInResponse(message, exchange);
        }
        super.doWriteResponse(message, response, exchange);
    }

    private void sendInternalException(Throwable e, HttpServletResponse response) throws IOException {
        log.error("Unable to respond from chain http trigger due to exception", e);
        response.sendError(500);
    }

    private void filterFieldsInResponse(Message message, Exchange exchange) {
        String filterIncludeFields = exchange.getProperty(CamelConstants.Properties.RESPONSE_FILTER_INCLUDE_FIELDS, String.class);
        String filterExcludeFields = exchange.getProperty(CamelConstants.Properties.RESPONSE_FILTER_EXCLUDE_FIELDS, String.class);

        ImmutableJsonFilterOptions.Builder filterOptionsBuilder = null;
        if (filterIncludeFields != null) {
            filterOptionsBuilder = getJsonFilterBuilder(filterOptionsBuilder)
                    .addAllIncludes(parseResponseFilterFields(filterIncludeFields));
        }
        if (filterExcludeFields != null) {
            filterOptionsBuilder = getJsonFilterBuilder(filterOptionsBuilder)
                    .addAllExcludes(parseResponseFilterFields(filterExcludeFields));
        }

        if (filterOptionsBuilder != null) {
            StringReader sr;
            StringWriter sw;
            try {
                String body = message.getBody(String.class);
                message.setBody(body);
                sr = new StringReader(body);
                sw = new StringWriter();
            } catch (Exception e) {
                log.warn("Unable to convert body for response filter", e);
                return;
            }
            JsonFilter filter = new JsonFilter(new JsonReader(sr), new JsonWriter(sw), filterOptionsBuilder.build());
            try {
                filter.process();
                message.setBody(sw.toString());
            } catch (IOException e) {
                log.warn("Failed to filter response", e);
                return;
            }
        }
    }

    private ImmutableJsonFilterOptions.Builder getJsonFilterBuilder(ImmutableJsonFilterOptions.Builder filterOptionsBuilder) {
        return filterOptionsBuilder == null ? ImmutableJsonFilterOptions.builder() : filterOptionsBuilder;
    }

    private List<String> parseResponseFilterFields(String filterFields) {
        return List.of(StringUtils.stripAll(filterFields.replace('.', '/').split(",")));
    }
}
