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

package org.qubership.integration.platform.parsers.resolvers;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.parsers.SpecificationParserException;

import java.util.Collections;
import java.util.Map;

import static org.qubership.integration.platform.parsers.schemas.SchemasConstants.DEFINITIONS_NODE_NAME;


@Slf4j
public class SwaggerSchemaResolver extends CommonSchemaResolver implements SchemaResolver {

    private static final String SCHEMA_RESOLVING_ERROR = "An error occurred during schema resolving";
    private static final String REF_KEY = "\"$ref\"";

    @Override
    public String resolveRef(String schemaRef, JsonNode componentsNode) {

        JsonNode currentComponentsNode = componentsNode.deepCopy();

        ObjectNode schemaNode = getSchemaNode(schemaRef, currentComponentsNode);

        Map<String, JsonNode> schemaRefs = getNestedRefs(schemaNode, currentComponentsNode, "swagger", Collections.EMPTY_LIST);

        return getResolvedSchema(schemaRef, schemaNode, schemaRefs);
    }

    // A schema that references no component is returned unchanged.
    public String resolveInlineSchema(String schemaAsString, JsonNode componentsNode) {
        if (!schemaAsString.contains(REF_KEY)) {
            return schemaAsString;
        }
        try {
            ObjectNode schemaNode = (ObjectNode) OBJECT_MAPPER.readTree(schemaAsString);
            Map<String, JsonNode> schemaRefs = getNestedRefs(schemaNode, componentsNode.deepCopy(), "swagger", Collections.emptyList());
            if (schemaRefs.isEmpty()) {
                return schemaAsString;
            }
            ObjectNode definitions = schemaNode.putObject(DEFINITIONS_NODE_NAME);
            schemaRefs.forEach(definitions::set);
            return OBJECT_MAPPER.writeValueAsString(schemaNode);
        } catch (JsonProcessingException e) {
            throw new SpecificationParserException(SCHEMA_RESOLVING_ERROR, e);
        }
    }
}
