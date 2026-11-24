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

package org.qubership.integration.platform.parsers.schemas.impl;

import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.MutablePair;
import org.qubership.integration.platform.parsers.schemas.Processor;
import org.qubership.integration.platform.parsers.schemas.SchemaProcessor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import static org.qubership.integration.platform.parsers.schemas.SchemasConstants.*;


@Slf4j
@Processor(OBJECT_SCHEMA_CLASS)
public class ObjectSchemaProcessor extends DefaultSchemaProcessor implements SchemaProcessor {
    private static final StringNode DEFAULT_SCHEMA_ID_VALUE = new StringNode("http://system.catalog/schemas/#/components/schemas/Schema");

    public ObjectSchemaProcessor(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public MutablePair<String, String> process(Schema<?> schema, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        ObjectSchema objectSchema = (ObjectSchema) schema;
        try {
            ObjectNode schemaAsNode = (ObjectNode) objectMapper.readTree(mapper.writeValueAsString(objectSchema));
            schemaAsNode.set(TYPE_NODE_NAME, OBJECT_TYPE_NODE);
            schemaAsNode.set(SCHEMA_ID_NODE_NAME, DEFAULT_SCHEMA_ID_VALUE);
            schemaAsNode.set(SCHEMA_HEADER_NODE_NAME, SCHEMA_HEADER_VALUE);
            return new MutablePair<>(objectSchema.get$ref(), objectMapper.writeValueAsString(schemaAsNode));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | JacksonException e) {
            log.error("Error during converting content object schema to JSON", e);
        }
        return new MutablePair<>();
    }
}
