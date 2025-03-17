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
import org.apache.camel.component.graphql.GraphqlEndpoint;
import org.apache.camel.component.graphql.GraphqlProducer;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.util.json.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.camel.Exchange.HTTP_RESPONSE_CODE;

public class GraphqlCustomProducer extends GraphqlProducer {

    private final static Set<String> EXCLUDE_HEADERS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        EXCLUDE_HEADERS.addAll(Set.of(
            Headers.GQL_VARIABLES_HEADER,
            Headers.GQL_QUERY_HEADER,
            HttpHeaders.CONTENT_LENGTH
        ));
    }

    public GraphqlCustomProducer(GraphqlEndpoint endpoint) {
        super(endpoint);
    }

    @Override
    public boolean process(Exchange exchange, AsyncCallback callback) {
        try {
            Map<String, Object> exchangeHeaders = exchange.getMessage().getHeaders();
            CloseableHttpClient httpClient = getEndpoint().getHttpclient();
            URI httpUri = getEndpoint().getHttpUri();
            String requestBody = buildRequestBody(getQuery(exchange),
                getEndpoint().getOperationName(),
                getVariables(exchange));
            HttpEntity requestEntity = new StringEntity(requestBody,
                ContentType.create("application/json", "UTF-8"));

            HttpPost httpPost = new HttpPost(httpUri);
            httpPost.setHeaders(convertHeaders(exchangeHeaders));
            httpPost.setHeader(HttpHeaders.ACCEPT, "application/json");
            httpPost.setHeader(HttpHeaders.ACCEPT_ENCODING, "gzip");
            httpPost.setEntity(requestEntity);

            CloseableHttpResponse response = httpClient.execute(httpPost);
            int statusCode = response.getCode();
            exchange.getMessage().setHeader(HTTP_RESPONSE_CODE, statusCode);
            String responseContent = response.getEntity() == null ? null : EntityUtils.toString(response.getEntity());
            Map<String, String> headers = convertHeaders(response.getHeaders());

            if (statusCode >= 400 && statusCode <= 599) {
                String statusText = response.getReasonPhrase();
                throw new HttpOperationFailedException(httpUri.toString(), statusCode, statusText, null,
                    headers, responseContent);
            }

            exchange.getMessage().setBody(responseContent);
            exchange.getMessage().getHeaders().putAll(headers); // alter exchange headers

        } catch (Exception e) {
            exchange.setException(e);
        }

        callback.done(true);
        return true;
    }

    private static Map<String, String> convertHeaders(Header[] responseHeaders) {
        if (responseHeaders == null || responseHeaders.length == 0) {
            return Collections.emptyMap();
        }

        return Arrays.stream(responseHeaders).collect(
            Collectors.toMap(NameValuePair::getName, NameValuePair::getValue, (a, b) -> b));
    }

    private static Header[] convertHeaders(Map<String, Object> requestHeaders) {
        if (requestHeaders == null || requestHeaders.isEmpty()) {
            return new Header[0];
        }

        return requestHeaders.entrySet().stream()
            .filter(entry -> entry.getValue() instanceof String && !EXCLUDE_HEADERS.contains(entry.getKey()))
            .map(entry -> new BasicHeader(entry.getKey(), entry.getValue()))
            .toArray(Header[]::new);
    }

    private String getQuery(Exchange exchange) throws InvalidPayloadException {
        String query = null;
        if (getEndpoint().getQuery() != null) {
            query = getEndpoint().getQuery();
        } else if (getEndpoint().getQueryHeader() != null) {
            query = exchange.getIn().getHeader(getEndpoint().getQueryHeader(), String.class);
        } else {
            query = exchange.getIn().getMandatoryBody(String.class);
        }
        return query;
    }

    private JsonObject getVariables(Exchange exchange) {
        JsonObject variables = null;
        if (getEndpoint().getVariables() != null) {
            variables = getEndpoint().getVariables();
        } else if (getEndpoint().getVariablesHeader() != null) {
            variables = exchange.getIn()
                .getHeader(getEndpoint().getVariablesHeader(), JsonObject.class);
        } else if (exchange.getIn().getBody() instanceof JsonObject) {
            variables = exchange.getIn().getBody(JsonObject.class);
        }
        return variables;
    }
}
