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

import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RequestFilterProcessor implements Processor {

    public final String requestFilterHeaderName;

    public RequestFilterProcessor(@Value("${camel.constants.request-filter-header.name}") String requestFilterHeaderName) {
        this.requestFilterHeaderName = CamelConstants.INTERNAL_PROPERTY_PREFIX + requestFilterHeaderName;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, String> headerAllowList = exchange.getProperty(requestFilterHeaderName, Map.class);
        if (headerAllowList == null) {
            return;
        }

        Map<String, Object> headers = exchange.getMessage().getHeaders();
        for (Map.Entry<String, String> filter : headerAllowList.entrySet()) {
            if (!headers.containsKey(filter.getKey()) ||
                    (!StringUtils.isBlank(filter.getValue()) && !headers.get(filter.getKey()).toString().equals(filter.getValue()))) {
                terminateExchange(exchange);
            }
        }
    }

    private static void terminateExchange(Exchange exchange) {
        exchange.getExchangeExtension().setInterrupted(true);
    }
}
