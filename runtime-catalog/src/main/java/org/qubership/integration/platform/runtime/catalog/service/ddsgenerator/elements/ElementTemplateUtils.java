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

package org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateSchema;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.OperationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Slf4j
@Component
public class ElementTemplateUtils {
    private final JsonSchemaParser schemaToDDSConverter;
    private final OperationService operationService;

    @Autowired
    public ElementTemplateUtils(JsonSchemaParser schemaToDDSConverter, OperationService operationService) {
        this.schemaToDDSConverter = schemaToDDSConverter;
        this.operationService = operationService;
    }

    public void addJSONSchemas(Map<String, Object> elementProps, Map<String, Object> elementTemplateProps) {
        // <content_type, schema>
        Map<String, TemplateSchema> templateRequestSchema = new HashMap<>();
        // <http_code, <content_type, schema>>
        Map<String, Map<String, TemplateSchema>> templateResponseSchema = new HashMap<>();

        String operationId = (String) elementProps.get(CamelOptions.OPERATION_ID);
        boolean operationPresent = StringUtils.isNotEmpty(operationId);

        if (!operationPresent) {
            return;
        }
        Operation operation;
        try {
            operation = operationService.getOperation(operationId);
        } catch (EntityNotFoundException e) {
            return;
        }
        Map<String, JsonNode> requestSchema = operation.getRequestSchema();

        if (requestSchema != null) {
            for (Map.Entry<String, JsonNode> entry : requestSchema.entrySet()) {
                String key = entry.getKey();
                // exclude keys that not contain content-type information
                if (!"parameters".equals(key)) {
                    JsonNode schemaNode = entry.getValue();
                    templateRequestSchema.put(key, schemaToDDSConverter.toProperties(schemaNode));
                }
            }
        }

        Map<String, JsonNode> responseSchema = operation.getResponseSchemas();
        if (responseSchema != null) {
            for (Map.Entry<String, JsonNode> entry : responseSchema.entrySet()) {
                String responseCode = entry.getKey();
                Map<String, TemplateSchema> codeMapping = new HashMap<>();
                templateResponseSchema.put(responseCode, codeMapping);

                JsonNode contentTypes = entry.getValue();
                for (Iterator<String> it = contentTypes.fieldNames(); it.hasNext(); ) {
                    String contentType = it.next();
                    JsonNode schemaNode = contentTypes.get(contentType);
                    codeMapping.put(contentType, schemaToDDSConverter.toProperties(schemaNode));
                }
            }
        }

        elementTemplateProps.put("requestSchema", templateRequestSchema);
        elementTemplateProps.put("responseSchema", templateResponseSchema);
    }
}
