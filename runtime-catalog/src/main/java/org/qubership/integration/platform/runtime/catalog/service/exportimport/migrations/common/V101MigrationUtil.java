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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.common;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class V101MigrationUtil {
    private V101MigrationUtil() {
    }

    public static ObjectNode moveFieldsToContentField(ObjectNode rootNode) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();

        result.set("id", rootNode.get("id"));
        result.set("name", rootNode.get("name"));

        // Move all fields except id and name to the content node
        ObjectNode contentNode = JsonNodeFactory.instance.objectNode();
        rootNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!"id".equals(key) && !"name".equals(key)) {
                contentNode.set(key, entry.getValue());
            }
        });
        result.set("content", contentNode);

        return result;
    }
}
