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
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.qubership.integration.platform.util.ElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.qubership.integration.platform.library.constants.CamelNames.M2M_PROP;
import static org.qubership.integration.platform.library.constants.CamelOptions.*;

@Slf4j
@Component
public class HttpProducerPropertiesBuilder  implements ElementPropertiesBuilder {
    public static final String REUSE_CONN_DEFAULT_VALUE = "true";
    public static final String M2M_DEFAULT_VALUE = "false";

    @Override
    public boolean applicableTo(Element element) {
        return Set.of(
                CamelNames.SERVICE_CALL_COMPONENT,
                CamelNames.HTTP_SENDER_COMPONENT,
                CamelNames.GRAPHQL_SENDER_COMPONENT
        ).contains(element.getType());
    }

    @Override
    public Map<String, String> build(Element element) {
        Map<String, String> properties = new HashMap<>();
        String type = element.getType();
        String reuseConn = null;
        String m2mEnabled = null;
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
                        Map<String, Object> props = element.getEnvironment()
                            .map(ServiceEnvironment::getProperties)
                            .orElse(Collections.emptyMap());
                        reuseConn = (String) props.get(CamelNames.REUSE_ESTABLISHED_CONN);
                    }
                    m2mEnabled = getM2MFlagFromServiceCall(element.getProperties());
                } else {
                    return Collections.emptyMap();
                }
            }
            case CamelNames.HTTP_SENDER_COMPONENT, CamelNames.GRAPHQL_SENDER_COMPONENT -> {
                reuseConn = ElementUtils.getPropertyAsString(element, CamelNames.REUSE_ESTABLISHED_CONN);
                m2mEnabled = ElementUtils.getPropertyAsString(element, M2M_PROP);
                if (element.getProperties().containsKey(URI)) {
                    properties.put(CamelNames.OPERATION_PATH_EXCHANGE, element.getProperties().get(URI).toString());
                }
            }
        }
        properties.put(CamelNames.REUSE_ESTABLISHED_CONN, StringUtils.isEmpty(reuseConn) ? REUSE_CONN_DEFAULT_VALUE : reuseConn);
        properties.put(M2M_PROP, StringUtils.isEmpty(m2mEnabled) ? M2M_DEFAULT_VALUE : m2mEnabled);
        return properties;
    }

    @Nullable
    private String getM2MFlagFromServiceCall(Map<String, Object> elementProperties) {
        String m2mEnabled = null;
        Map<String, String> authorizationConfig = (Map<String, String>) elementProperties.get(AUTHORIZATION_CONFIGURATION);
        if (authorizationConfig != null) {
            m2mEnabled = String.valueOf(M2M_PROP.equalsIgnoreCase(authorizationConfig.get(TYPE)));
        }
        return m2mEnabled;
    }
}
