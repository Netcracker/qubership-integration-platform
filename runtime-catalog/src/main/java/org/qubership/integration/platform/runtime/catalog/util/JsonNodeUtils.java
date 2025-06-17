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

package org.qubership.integration.platform.runtime.catalog.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;

public final class JsonNodeUtils {

    private JsonNodeUtils() {
    }

    public static JsonNode removeEmptyNodes(JsonNode rootNode) {
        Iterator<JsonNode> iterator = rootNode.elements();
        while (iterator.hasNext()) {
            JsonNode node = iterator.next();
            if (node.isMissingNode() || node.isNull()) {
                iterator.remove();
            } else if (node.isContainerNode()) {
                removeEmptyNodes(node);
                if (node.isEmpty()) {
                    iterator.remove();
                }
            }
        }
        return rootNode;
    }
}
