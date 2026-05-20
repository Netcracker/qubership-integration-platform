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
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class QueryParameterFilterProcessor implements Processor {

    private static final String SERVICE_CALL_SKIP_EMPTY_QUERY_PARAMS = "serviceCallSkipEmptyQueryParams";
    private static final String INTERNAL_PROPERTY_QUERY_PARAM_PREFIX  = "internalProperty_queryParam_";

    @Override
    public void process(Exchange exchange) throws Exception {
        Message message = exchange.getMessage();
        String uri = message.getHeader(CamelConstants.Headers.HTTP_URI, String.class);

        if (StringUtils.isEmpty(uri)) {
            return;
        }

        Object skipEmptyParamsProperty = exchange.getProperty(SERVICE_CALL_SKIP_EMPTY_QUERY_PARAMS);
        boolean shouldSkipEmptyParams = skipEmptyParamsProperty != null && Boolean.parseBoolean(String.valueOf(skipEmptyParamsProperty));

        if (shouldSkipEmptyParams) {
            String finalUri = generateFilteredQueryParams(uri, exchange);
            message.setHeader(CamelConstants.Headers.HTTP_URI, finalUri);
        }
    }

    private String generateFilteredQueryParams(String baseUri, Exchange exchange) {
        StringBuilder queryString = new StringBuilder();

        exchange.getProperties().entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(INTERNAL_PROPERTY_QUERY_PARAM_PREFIX))
            .forEach(entry -> {
                String paramName = entry.getKey().substring(INTERNAL_PROPERTY_QUERY_PARAM_PREFIX.length());
                String paramValue = (String) entry.getValue();

                if (!StringUtils.isEmpty(paramValue)) {
                    if (queryString.isEmpty()) {
                        queryString.append("?");
                    } else {
                        queryString.append("&");
                    }
                    queryString.append(paramName).append("=").append(paramValue);
                }
            });

        return baseUri + queryString;
    }

}
