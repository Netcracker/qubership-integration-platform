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

package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.URI;

@Slf4j
@Component
public class HttpProducerPropertiesBuilder  implements ElementPropertiesBuilder {
    public static final String REUSE_CONN_DEFAULT_VALUE = "true";

    @Override
    public boolean applicableTo(ChainElement element) {
        return Set.of(
                CamelNames.SERVICE_CALL_COMPONENT,
                CamelNames.HTTP_SENDER_COMPONENT,
                CamelNames.GRAPHQL_SENDER_COMPONENT
        ).contains(element.getType());
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        Map<String, String> properties = new HashMap<>();
        String type = element.getType();
        String reuseConn = null;
        switch (type) {
            case CamelNames.SERVICE_CALL_COMPONENT -> {
                String protocol = (String) element.getProperties().get(CamelNames.OPERATION_PROTOCOL_TYPE_PROP);
                Object operationPath = element.getProperties().get(CamelOptions.OPERATION_PATH);
                if (operationPath != null) {
                    properties.put(CamelOptions.OPERATION_PATH, operationPath.toString());
                }
                if (CamelNames.OPERATION_PROTOCOL_TYPE_HTTP.equals(protocol) || CamelNames.OPERATION_PROTOCOL_TYPE_GRAPHQL.equals(protocol)) {
                    // get prop from element
                    Map<String, String> additionalParams = (Map<String, String>) element.getProperties().get(CamelNames.SERVICE_CALL_ADDITIONAL_PARAMETERS);
                    if (additionalParams != null && additionalParams.containsKey(CamelNames.REUSE_ESTABLISHED_CONN)) {
                        reuseConn = additionalParams.get(CamelNames.REUSE_ESTABLISHED_CONN);
                    } else { // get prop from ENV
                        if (element.getEnvironment() != null && element.getEnvironment().getProperties() != null) {
                            reuseConn = (String) element.getEnvironment().getProperties().get(CamelNames.REUSE_ESTABLISHED_CONN);
                        }
                    }
                } else {
                    return Collections.emptyMap();
                }
            }
            case CamelNames.HTTP_SENDER_COMPONENT, CamelNames.GRAPHQL_SENDER_COMPONENT -> {
                reuseConn = element.getPropertyAsString(CamelNames.REUSE_ESTABLISHED_CONN);
                if (element.getProperties().containsKey(URI)) {
                    properties.put(CamelNames.OPERATION_PATH_EXCHANGE, element.getProperties().get(URI).toString());
                }
            }
        }
        properties.put(CamelNames.REUSE_ESTABLISHED_CONN, StringUtils.isEmpty(reuseConn) ? REUSE_CONN_DEFAULT_VALUE : reuseConn);
        return properties;
    }
}
