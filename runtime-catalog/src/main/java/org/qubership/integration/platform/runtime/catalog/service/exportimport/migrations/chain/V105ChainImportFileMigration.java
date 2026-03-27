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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.*;

@Slf4j
@Component
public class V105ChainImportFileMigration implements ChainImportFileMigration {
    @Override
    public int getVersion() {
        return 105;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) throws JsonProcessingException {
        log.debug("Applying chain migration: {}", getVersion());

        ObjectNode rootNode = fileNode.deepCopy();
        rootNode.path(CONTENT).path(ELEMENTS).forEach(this::processElementsAction);

        return rootNode;
    }

    private void processElementsAction(JsonNode element) {
        element.path(CHILDREN).forEach(this::processElementsAction);
        JsonNode elemType = element.path(TYPE);

        if (elemType != null && "http-trigger".equals(elemType.asText())) {
            JsonNode propertiesNode = element.path(PROPERTIES);
            if (!(propertiesNode instanceof ObjectNode properties)) {
                return;
            }

            if ("RBAC".equals(properties.path("accessControlType").asText())) {
                if (properties.has("roles")) {
                    JsonNode roles = properties.get("roles");
                    if (roles.isEmpty()) {
                        properties.remove("roles");
                        properties.put("accessControlType", "NONE");
                    }
                } else {
                    properties.put("accessControlType", "NONE");
                }
            }
        }
    }
}
