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

package org.qubership.integration.platform.runtime.catalog.service.schemas.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.MutablePair;
import org.qubership.integration.platform.runtime.catalog.service.schemas.Processor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.SchemaProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.runtime.catalog.service.schemas.SchemasConstants.*;

@Slf4j
@Service
@Processor(ARRAY_SCHEMA_CLASS)
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class ArraySchemaProcessor extends DefaultSchemaProcessor implements SchemaProcessor {

    private final Map<String, SchemaProcessor> schemaProcessorMap = new HashMap<>();

    @Autowired
    public ArraySchemaProcessor(
            List<SchemaProcessor> schemaProcessors,
            @Qualifier("openApiObjectMapper") ObjectMapper objectMapper
    ) {
        super(objectMapper);
        for (SchemaProcessor schemaProcessor : schemaProcessors) {
            Processor processorAnnotation = schemaProcessor.getClass().getAnnotation(Processor.class);
            if (processorAnnotation != null && !processorAnnotation.value().equals(ARRAY_SCHEMA_CLASS)) {
                this.schemaProcessorMap.put(processorAnnotation.value(), schemaProcessor);
            }
        }
    }

    @Override
    public MutablePair<String, String> process(Schema<?> schema) {
        ArraySchema arraySchema = (ArraySchema) schema;
        String ref = arraySchema.getItems().get$ref();
        Schema<?> itemsSchema = arraySchema.getItems();
        if (ref != null) {
            return new MutablePair<>(arraySchema.getItems().get$ref(), arraySchema.getItems().toString());
        }
        if (itemsSchema != null) {
            SchemaProcessor schemaProcessor = schemaProcessorMap.getOrDefault(itemsSchema.getClass().getSimpleName(),
                    schemaProcessorMap.get(DEFAULT_SCHEMA_CLASS));
            ObjectNode arraySchemaNode = objectMapper.createObjectNode();

            MutablePair<String, String> processedSchemaPair = schemaProcessor.process(itemsSchema);
            arraySchemaNode.set(TYPE_NODE_NAME, ARRAY_TYPE_NODE);

            try {
                arraySchemaNode.set(ITEMS_NODE_NAME, objectMapper.readTree(processedSchemaPair.getRight()));
                processedSchemaPair.setRight(objectMapper.writeValueAsString(arraySchemaNode));
            } catch (JsonProcessingException e) {
                log.error("Error during converting content string schema to JSON", e);
            }

            return processedSchemaPair;
        }
        return new MutablePair<>();
    }

    @Override
    public ObjectNode applySchemaType(String schemaAsString) {
        try {
            JsonNode coreSchema = objectMapper.readTree(schemaAsString);

            ObjectNode itemsNode = objectMapper.createObjectNode();
            itemsNode.set(TYPE_NODE_NAME, coreSchema.get(TYPE_NODE_NAME));
            itemsNode.set(PROPERTIES_FIELD_NAME, coreSchema.get(PROPERTIES_FIELD_NAME));
            itemsNode.set(REQUIRED, coreSchema.get(REQUIRED));
            ObjectNode resultSchema = objectMapper.createObjectNode();
            resultSchema.set(SCHEMA_ID_NODE_NAME, coreSchema.get(SCHEMA_ID_NODE_NAME));
            resultSchema.set(SCHEMA_HEADER_NODE_NAME, coreSchema.get(SCHEMA_HEADER_NODE_NAME));
            resultSchema.set(TYPE_NODE_NAME, ARRAY_TYPE_NODE);
            resultSchema.set(ITEMS_NODE_NAME, itemsNode);
            resultSchema.set(DEFINITIONS_NODE_NAME, coreSchema.get(DEFINITIONS_NODE_NAME));
            return resultSchema;
        } catch (JsonProcessingException e) {
            log.error("Error during converting content string schema to JSON", e);
        }
        return objectMapper.createObjectNode();
    }
}
