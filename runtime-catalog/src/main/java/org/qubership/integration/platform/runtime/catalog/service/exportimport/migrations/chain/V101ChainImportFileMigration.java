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
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.common.MigrationUtil;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class V101ChainImportFileMigration implements ChainImportFileMigration {
    @Override
    public int getVersion() {
        return 101;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) throws JsonProcessingException {
        log.debug("Applying chain migration: {}", getVersion());

        // Move all fields except id and name to the content node
        ObjectNode result = MigrationUtil.moveFieldsToContentField(fileNode.deepCopy());

        // Rename properties-filename property to propertiesFilename
        renameField(result, "properties-filename", "propertiesFilename", true);
        // Make new default value from "" to "org.apache.kafka.common.serialization.StringSerializer" for keySerializer
        setNewValueToEmptyField(result, "keySerializer", "org.apache.kafka.common.serialization.StringSerializer");
        // Rename element-type property to type in all elements
        result.path("content").path("elements")
            .forEach(elementNode -> renameField(elementNode, "element-type", "type", false));
        return result;
    }

    private void renameField(JsonNode node, String from, String to, boolean recursive) {
        if (node.isObject()) {
            if (node.has(from)) {
                if (node.has(to)) {
                    log.error("Object already has field {}", to);
                } else {
                    ((ObjectNode) node).set(to, node.get(from));
                    ((ObjectNode) node).remove(from);
                }
            }
        }
        if (recursive) {
            node.forEach(child -> renameField(child, from, to, recursive));
        }
    }

    private void setNewValueToEmptyField(JsonNode node, String fieldName, String newValue) {
        if (node.isObject()) {
            JsonNode field = node.get(fieldName);
            if (field != null && (field.isNull() || field.asText().isBlank())) {
                ((ObjectNode) node).put(fieldName, newValue);
            }
            node.fields().forEachRemaining(entry -> setNewValueToEmptyField(entry.getValue(), fieldName, newValue));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                setNewValueToEmptyField(item, fieldName, newValue);
            }
        }
    }
}
