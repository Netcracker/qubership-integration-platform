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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.MutablePair;
import org.qubership.integration.platform.runtime.catalog.service.schemas.Processor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.SchemaProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import static org.qubership.integration.platform.runtime.catalog.service.schemas.SchemasConstants.*;


@Slf4j
@Service
@Processor(UUID_SCHEMA_CLASS)
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class UUIDSchemaProcessor extends DefaultSchemaProcessor implements SchemaProcessor {

    @Autowired
    public UUIDSchemaProcessor(@Qualifier("openApiObjectMapper") ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public MutablePair<String, String> process(Schema<?> schema) {
        UUIDSchema uuidSchema = (UUIDSchema) schema;
        ObjectNode schemaAsNode = objectMapper.convertValue(uuidSchema, ObjectNode.class);
        schemaAsNode.set(TYPE_NODE_NAME, STRING_TYPE_NODE);
        schemaAsNode.set(FORMAT_NODE_NAME, UUID_TYPE_NODE);
        try {
           String schemaAsString = objectMapper.writeValueAsString(schemaAsNode);
           return new MutablePair<>(uuidSchema.get$ref(), schemaAsString);
        } catch (JsonProcessingException e) {
            log.error("Error during converting content uuid schema to JSON", e);
        }
        return new MutablePair<>();
    }
}
