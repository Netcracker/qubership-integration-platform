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

package org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.ElementTemplateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class HttpTriggerDDSConverter extends ElementDDSConverter {
    private static final Map<String, String> TYPE_MAPPING = Map.of(
            CamelNames.HTTP_TRIGGER_COMPONENT, "HTTP Trigger"
    );

    private final ElementTemplateUtils elementTemplateUtils;
    private final ObjectMapper jsonMapper;
    private final RoutePrefixProvider routePrefixProvider;

    @Autowired
    public HttpTriggerDDSConverter(
            ElementTemplateUtils elementTemplateUtils,
            @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper,
            RoutePrefixProvider routePrefixProvider
    ) {
        this.elementTemplateUtils = elementTemplateUtils;
        this.jsonMapper = jsonMapper;
        this.routePrefixProvider = routePrefixProvider;
    }

    @Override
    protected Map<String, String> getTypeMapping() {
        return TYPE_MAPPING;
    }

    @Override
    public TemplateChainElement convert(ChainElement element) {
        Map<String, Object> elementProps = element.getProperties();
        Map<String, Object> elementTemplateProps = new HashMap<>();
        TemplateChainElement.TemplateChainElementBuilder builder = getBuilder(element);
        builder.properties(elementTemplateProps);

        String contextPath = (String) elementProps.getOrDefault(CamelOptions.CONTEXT_PATH, "");
        if (StringUtils.isEmpty(contextPath)) {
            contextPath = (String) elementProps.getOrDefault(CamelOptions.OPERATION_PATH, "");
        }
        boolean isExternal = (boolean) elementProps.getOrDefault(CamelOptions.IS_EXTERNAL_ROUTE, true);
        String httpMethodRestrict = (String) elementProps.get(CamelOptions.HTTP_METHOD_RESTRICT);
        List<String> allowedContentTypes = (List<String>) elementProps.get(CamelOptions.ALLOWED_CONTENT_TYPES);
        elementTemplateProps.put("endpointUri", routePrefixProvider.getRoutePrefix(isExternal) + StringUtils.strip(contextPath, "/"));
        elementTemplateProps.put("endpointAllowedMethods", httpMethodRestrict == null ? "ALL" : httpMethodRestrict);
        elementTemplateProps.put("validateRequestContentType", allowedContentTypes == null || allowedContentTypes.isEmpty()
                ? "ALL" : String.join(", ", allowedContentTypes));

        try {
            List<String> rolesList = (List<String>) elementProps.getOrDefault("roles", Collections.emptyList());
            Map<String, Object> value = new HashMap<>();
            value.put("type", elementProps.get(CamelOptions.ACCESS_CONTROL_TYPE));
            value.put("roles", (rolesList.isEmpty() ? "Any role" : jsonMapper.writeValueAsString(rolesList))
                    .replace("\"", ""));
            value.put("resource", elementProps.get(CamelOptions.ABAC_RESOURCE));
            elementTemplateProps.put("accessControl", value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        elementTemplateUtils.addJSONSchemas(elementProps, elementTemplateProps);

        return builder.build();
    }
}
