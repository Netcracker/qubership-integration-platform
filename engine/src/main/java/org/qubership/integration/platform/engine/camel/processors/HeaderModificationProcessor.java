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
import org.apache.camel.language.simple.SimpleLanguage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HeaderModificationProcessor implements Processor {

    private final SimpleLanguage simpleInterpreter;

    @Autowired
    public HeaderModificationProcessor(SimpleLanguage simpleInterpreter) {
        this.simpleInterpreter = simpleInterpreter;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, String> headersToAdd = exchange.getProperty(CamelConstants.Properties.HEADER_MODIFICATION_TO_ADD, Map.class);
        List<String> headerPatternsToRemove = exchange.getProperty(CamelConstants.Properties.HEADER_MODIFICATION_TO_REMOVE, List.class);

        String[] headersToKeep = new String[0];
        if (headersToAdd != null) {
            for (Map.Entry<String, String> entry : headersToAdd.entrySet()) {
                if (StringUtils.isEmpty(entry.getValue())) {
                    continue;
                }
                exchange.getMessage().setHeader(entry.getKey(), evaluateSimpleExpression(exchange, entry.getValue()));
            }
            headersToKeep = headersToAdd.keySet().toArray(new String[0]);
        }

        if (headerPatternsToRemove != null) {
            for (String pattern : headerPatternsToRemove) {
                exchange.getMessage().removeHeaders(pattern, headersToKeep);
            }
        }
    }

    private String evaluateSimpleExpression(Exchange exchange, String str) {
        return simpleInterpreter.createExpression(str).evaluate(exchange, String.class);
    }
}
