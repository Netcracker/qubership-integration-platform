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

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DataTypeUtilsTest {
    @Test
    void testUpdateDefinitionMapFromScalarTypes() {
        Map<String, TypeDefinition> definitionMap = Map.of(
                "foo",
                new TypeDefinition("foo", null, null)
        );
        Stream.of(
                new NullType(null),
                new IntegerType(null),
                new StringType(null),
                new BooleanType(null)
        ).forEach(type -> {
            var result = DataTypeUtils.updateDefinitionMapFromType(definitionMap, type);
            assertEquals(definitionMap, result);
        });
    }

    @Test
    void testUpdateDefinitionMapFromComplexTypes() {
        TypeDefinition fooDefinition = new TypeDefinition("foo", null, null);
        Collection<TypeDefinition> definitions = List.of(fooDefinition);
        Stream.of(
                new ObjectType(null, definitions, null),
                new ReferenceType("foo", definitions, null),
                new ArrayType(null, definitions, null),
                new AllOfType(null, definitions, null),
                new AnyOfType(null, definitions, null),
                new OneOfType(null, definitions, null)
        ).forEach(type -> {
            TypeDefinition barDefinition = new TypeDefinition("bar", null, null);
            Map<String, TypeDefinition> definitionMap = Map.of("bar", barDefinition);
            var result = DataTypeUtils.updateDefinitionMapFromType(definitionMap, type);
            assertEquals(Map.of("foo", fooDefinition, "bar", barDefinition), result);
        });
    }

    @Test
    void testResolveType() {
        TypeDefinition fooDefinition = new TypeDefinition("foo", "foo",
                new ReferenceType("bar", null, null));
        TypeDefinition barDefinition = new TypeDefinition("bar", "bar",
                new StringType(null));
        DataType type = new ReferenceType("foo", List.of(fooDefinition), null);
        var result = DataTypeUtils.resolveType(type, Map.of("bar", barDefinition));
        assertTrue(result.type() instanceof StringType);
        assertEquals(Map.of("foo", fooDefinition, "bar", barDefinition), result.definitionMap());
    }

    @Test
    void testResolveTypeThrowsExceptionWhenReferencedDefinitionNotFound() {
        Exception exception = assertThrows(
                Exception.class,
                () -> DataTypeUtils.resolveType(
                        new ReferenceType("foo", null, null),
                        Collections.emptyMap()));
        assertTrue(exception.getMessage().contains("foo"));
    }
}

