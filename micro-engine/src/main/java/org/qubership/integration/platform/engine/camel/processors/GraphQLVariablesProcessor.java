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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.util.json.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class GraphQLVariablesProcessor implements Processor {
    private final ObjectMapper objectMapper;

    @Autowired
    public GraphQLVariablesProcessor(@Qualifier("jsonMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String variablesJSON = exchange.getProperty(Properties.GQL_VARIABLES_JSON, String.class);

        JsonObject variables = StringUtils.isNotEmpty(variablesJSON)
                ? objectMapper.readValue(variablesJSON, JsonObject.class)
                : new JsonObject();

        exchange.getMessage().setHeader(Headers.GQL_VARIABLES_HEADER, variables);
    }
}
