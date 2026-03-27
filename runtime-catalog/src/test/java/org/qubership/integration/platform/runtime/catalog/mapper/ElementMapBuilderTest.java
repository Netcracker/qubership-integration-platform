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

package org.qubership.integration.platform.runtime.catalog.mapper;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Attribute;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.ObjectSchema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElementMapBuilderTest {
    @Test
    void testBuildAttributeMap() {
        ElementMapBuilder elementMapBuilder = new ElementMapBuilder();
        Attribute a1 = new Attribute("a1", "a1", new StringType(null), null, null, null);
        Attribute a2 = new Attribute("a2", "a2", new StringType(null), null, null, null);
        ObjectType objectType = new ObjectType(new ObjectSchema("o1", List.of(a1, a2), null), null, null);
        TypeDefinition typeDefinition = new TypeDefinition("foo", "foo", objectType);
        ArrayType arrayType = new ArrayType(
                new ReferenceType("foo", null, null),
                List.of(typeDefinition),
                null
        );
        Attribute a3 = new Attribute("a3", "a3", arrayType, null, null, null);
        var result = elementMapBuilder.buildElementMap(
                new ObjectType(new ObjectSchema("o2", List.of(a3), null), null, null)
        );
        assertEquals(
                Map.of(
                        "a1", new ElementMapBuilder.ElementWithContext(
                                a1, new ElementMapBuilder.ElementContext(
                                        Collections.singletonMap("foo", typeDefinition))),
                        "a2", new ElementMapBuilder.ElementWithContext(
                                a2, new ElementMapBuilder.ElementContext(
                                        Collections.singletonMap("foo", typeDefinition))),
                        "a3", new ElementMapBuilder.ElementWithContext(
                                a3, new ElementMapBuilder.ElementContext(Collections.emptyMap()))
                ),
                result
        );
    }
}
