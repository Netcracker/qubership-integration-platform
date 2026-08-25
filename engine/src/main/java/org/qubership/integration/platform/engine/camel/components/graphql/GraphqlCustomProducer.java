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

package org.qubership.integration.platform.engine.camel.components.graphql;

import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.InvalidPayloadException;
import org.apache.camel.TypeConverter;
import org.apache.camel.component.graphql.GraphqlProducer;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.camel.util.json.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.net.URI;
import java.util.Map;

public class GraphqlCustomProducer extends GraphqlProducer {
    private static final int OK_STATUS_FROM = 200;
    private static final int OK_STATUS_TO = 299;

    public GraphqlCustomProducer(GraphqlCustomEndpoint endpoint) {
        super(endpoint);
    }

    @Override
    public GraphqlCustomEndpoint getEndpoint() {
        return (GraphqlCustomEndpoint) super.getEndpoint();
    }

    @Override
    public boolean process(Exchange exchange, AsyncCallback callback) {
        GraphqlCustomEndpoint endpoint = getEndpoint();
        try {
            URI httpUri = endpoint.getHttpUri();
            String requestBody = buildRequestBody(
                resolveQuery(exchange), endpoint.getOperationName(), resolveVariables(exchange));

            HttpPost request = new HttpPost(httpUri);
            request.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
            request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
            copyExchangeHeaders(exchange, request);
            request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

            endpoint.getHttpClient().execute(request, response -> {
                int statusCode = response.getCode();
                if (isOkStatus(statusCode) || !endpoint.isThrowExceptionOnFailure()) {
                    populateResponse(exchange, response, endpoint.getHeaderFilterStrategy(), statusCode);
                } else {
                    setResponseStatus(exchange, response, statusCode);
                    exchange.setException(
                        populateHttpOperationFailedException(exchange, response, statusCode));
                }
                return null;
            });
        } catch (Exception e) {
            exchange.setException(e);
        }

        callback.done(true);
        return true;
    }

    void copyExchangeHeaders(Exchange exchange, HttpPost request) {
        HeaderFilterStrategy filterStrategy = getEndpoint().getHeaderFilterStrategy();
        TypeConverter converter = exchange.getContext().getTypeConverter();

        for (Map.Entry<String, Object> header : exchange.getMessage().getHeaders().entrySet()) {
            String name = header.getKey();
            if (isOperationHeader(name)) {
                continue;
            }

            String value = converter.tryConvertTo(String.class, exchange, header.getValue());
            if (value == null) {
                continue;
            }
            if (filterStrategy != null && filterStrategy.applyFilterToCamelHeaders(name, value, exchange)) {
                continue;
            }

            request.addHeader(name, value);
        }
    }

    private boolean isOperationHeader(String name) {
        return name.equalsIgnoreCase(getEndpoint().getQueryHeader())
            || name.equalsIgnoreCase(getEndpoint().getVariablesHeader());
    }

    private String resolveQuery(Exchange exchange) throws InvalidPayloadException {
        GraphqlCustomEndpoint endpoint = getEndpoint();
        if (endpoint.getQuery() != null) {
            return endpoint.getQuery();
        }
        if (endpoint.getQueryHeader() != null) {
            return exchange.getMessage().getHeader(endpoint.getQueryHeader(), String.class);
        }
        return exchange.getMessage().getMandatoryBody(String.class);
    }

    private JsonObject resolveVariables(Exchange exchange) {
        GraphqlCustomEndpoint endpoint = getEndpoint();
        if (endpoint.getVariables() != null) {
            return endpoint.getVariables();
        }
        if (endpoint.getVariablesHeader() != null) {
            return exchange.getMessage().getHeader(endpoint.getVariablesHeader(), JsonObject.class);
        }
        return exchange.getMessage().getBody() instanceof JsonObject variables ? variables : null;
    }

    private static void setResponseStatus(Exchange exchange, ClassicHttpResponse response, int statusCode) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
        if (response.getReasonPhrase() != null) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_TEXT, response.getReasonPhrase());
        }
    }

    private static boolean isOkStatus(int statusCode) {
        return statusCode >= OK_STATUS_FROM && statusCode <= OK_STATUS_TO;
    }
}
