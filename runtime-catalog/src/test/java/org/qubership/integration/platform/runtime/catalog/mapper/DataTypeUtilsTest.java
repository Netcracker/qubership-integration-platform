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
import org.qubership.integration.platform.mapper.build.DataTypeUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DataTypeUtilsTest {
    @Test
    void testUpdateDefinitionMapFromScalarTypes() {
        Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitionMap = Map.of(
                "foo",
                new org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition("foo", null, null)
        );
        Stream.of(
                new org.qubership.integration.platform.mapper.model.datatypes.NullType(null),
                new org.qubership.integration.platform.mapper.model.datatypes.IntegerType(null),
                new org.qubership.integration.platform.mapper.model.datatypes.StringType(null),
                new org.qubership.integration.platform.mapper.model.datatypes.BooleanType(null)
        ).forEach(type -> {
            var result = DataTypeUtils.updateDefinitionMapFromType(definitionMap, type);
            assertEquals(definitionMap, result);
        });
    }

    @Test
    void testUpdateDefinitionMapFromComplexTypes() {
        org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition fooDefinition = new org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition("foo", null, null);
        Collection<org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitions = List.of(fooDefinition);
        Stream.of(
                new org.qubership.integration.platform.mapper.model.datatypes.ObjectType(null, definitions, null),
                new org.qubership.integration.platform.mapper.model.datatypes.ReferenceType("foo", definitions, null),
                new org.qubership.integration.platform.mapper.model.datatypes.ArrayType(null, definitions, null),
                new org.qubership.integration.platform.mapper.model.datatypes.AllOfType(null, definitions, null),
                new org.qubership.integration.platform.mapper.model.datatypes.AnyOfType(null, definitions, null),
                new org.qubership.integration.platform.mapper.model.datatypes.OneOfType(null, definitions, null)
        ).forEach(type -> {
            org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition barDefinition = new org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition("bar", null, null);
            Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitionMap = Map.of("bar", barDefinition);
            var result = DataTypeUtils.updateDefinitionMapFromType(definitionMap, type);
            assertEquals(Map.of("foo", fooDefinition, "bar", barDefinition), result);
        });
    }

    @Test
    void testResolveType() {
        org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition fooDefinition = new org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition("foo", "foo",
                new org.qubership.integration.platform.mapper.model.datatypes.ReferenceType("bar", null, null));
        org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition barDefinition = new org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition("bar", "bar",
                new org.qubership.integration.platform.mapper.model.datatypes.StringType(null));
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = new org.qubership.integration.platform.mapper.model.datatypes.ReferenceType("foo", List.of(fooDefinition), null);
        var result = DataTypeUtils.resolveType(type, Map.of("bar", barDefinition));
        assertTrue(result.type() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
        assertEquals(Map.of("foo", fooDefinition, "bar", barDefinition), result.definitionMap());
    }

    @Test
    void testResolveTypeThrowsExceptionWhenReferencedDefinitionNotFound() {
        Exception exception = assertThrows(
                Exception.class,
                () -> DataTypeUtils.resolveType(
                        new org.qubership.integration.platform.mapper.model.datatypes.ReferenceType("foo", null, null),
                        Collections.emptyMap()));
        assertTrue(exception.getMessage().contains("foo"));
    }
}
