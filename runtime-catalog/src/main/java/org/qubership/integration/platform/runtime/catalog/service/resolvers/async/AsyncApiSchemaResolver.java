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

package org.qubership.integration.platform.runtime.catalog.service.resolvers.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.CommonSchemaResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.SchemaResolver;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Service
@Slf4j
public class AsyncApiSchemaResolver extends CommonSchemaResolver implements SchemaResolver {

    private static final String COMPONENTS_PREFIX = "#/components";
    private static final String REF_FIELD_NAME = "$ref";

    @Override
    public String resolveRef(String schemaRef, JsonNode componentsNode) {

        JsonNode currentComponentsNode = componentsNode.deepCopy();

        ObjectNode schemaNode = getSchemaNode(schemaRef, currentComponentsNode);

        convertPayloadToSchemaNode(schemaNode);

        resolveInlineRef(schemaNode, currentComponentsNode);

        Map<String, JsonNode> schemaRefs = getNestedRefs(schemaNode, currentComponentsNode, "asyncapi", Collections.emptyList());

        return getResolvedSchema(schemaRef, schemaNode, schemaRefs);
    }

    private void resolveInlineRef(ObjectNode schemaNode, JsonNode componentsNode) {
        if (!schemaNode.has(REF_FIELD_NAME) || schemaNode.has("type")) {
            return;
        }
        String ref = schemaNode.get(REF_FIELD_NAME).asText();
        String path = ref.replace(COMPONENTS_PREFIX, "");
        JsonNode resolved = componentsNode.at(path);
        if (!resolved.isMissingNode() && resolved.isObject()) {
            schemaNode.removeAll();
            schemaNode.setAll(((ObjectNode) resolved).deepCopy());
        }
    }
}
