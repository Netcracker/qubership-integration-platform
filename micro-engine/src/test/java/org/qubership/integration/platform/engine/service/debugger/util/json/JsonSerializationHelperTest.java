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

package org.qubership.integration.platform.engine.service.debugger.util.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class JsonSerializationHelperTest {

    static class Node {
        public String name;
        public Node next;

        public Node(String name) {
            this.name = name;
        }
    }

    @Test
    void shouldReturnPlainJsonWhenNoCycles() throws Exception {
        Node a = new Node("a");
        a.next = null;

        String json = JsonSerializationHelper.serializeJson(a);
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals("a", root.get("name").asText());
        assertTrue(root.get("next").isNull());
        assertNull(root.get("@json-id"));
        assertNull(root.get("reference"));
    }

    @Test
    void shouldWrapSelfReferenceWithJsonIdWhenObjectReferencesItself() throws Exception {
        Node a = new Node("a");
        a.next = a;

        String json = JsonSerializationHelper.serializeJson(a);
        JsonNode root = new ObjectMapper().readTree(json);

        assertTrue(root.has("@json-id"));
        assertTrue(root.has("reference"));

        String id = root.get("@json-id").asText();
        JsonNode ref = root.get("reference");
        assertEquals("a", ref.get("name").asText());
        assertEquals(id, ref.get("next").asText());
    }

    @Test
    void shouldReplaceBackReferenceWithIdWhenTwoNodesReferenceEachOther() throws Exception {
        Node a = new Node("a");
        Node b = new Node("b");
        a.next = b;
        b.next = a;

        String json = JsonSerializationHelper.serializeJson(a);
        JsonNode root = new ObjectMapper().readTree(json);

        assertTrue(root.has("@json-id"));
        assertTrue(root.has("reference"));

        String id = root.get("@json-id").asText();
        JsonNode ref = root.get("reference");
        assertEquals("a", ref.get("name").asText());

        JsonNode bNode = ref.get("next");
        assertEquals("b", bNode.get("name").asText());
        assertEquals(id, bNode.get("next").asText());
    }
}
