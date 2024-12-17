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

import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Attribute;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

public class DataTypeUtils {
    public record ResolveResult(DataType type, Map<String, TypeDefinition> definitionMap) {}

    public static ResolveResult resolveType(DataType type, Map<String, TypeDefinition> definitionMap) {
        while (type instanceof ReferenceType referenceType) {
            definitionMap = updateDefinitionMapFromType(definitionMap, referenceType);
            String id = referenceType.getDefinitionId();
            TypeDefinition definition = definitionMap.get(id);
            if (isNull(definition)) {
                String message = String.format("Failed to resolve type definition with id: %s.", id);
                throw new MapperException(message);
            }
            type = definition.getType();
        }
        return new ResolveResult(type, definitionMap);
    }

    public static Map<String, TypeDefinition> updateDefinitionMapFromType(
            Map<String, TypeDefinition> definitionMap,
            DataType dataType
    ) {
        if (dataType instanceof TypeWithDefinitions type && !type.getDefinitions().isEmpty()) {
            Map<String, TypeDefinition> result = new HashMap<>(definitionMap);
            type.getDefinitions().forEach(definition -> result.put(definition.getId(), definition));
            return result;
        } else {
            return definitionMap;
        }
    }

    public static Optional<DataType> findBranchByAttributeId(
            CompoundType type,
            String attributeId,
            Map<String, TypeDefinition> definitionMap
    ) {
        return getBranches(type, definitionMap)
                .filter(resolveResult -> resolveResult.type() instanceof ComplexType)
                .filter(resolveResult -> hasAttribute(resolveResult.type(), attributeId, resolveResult.definitionMap()))
                .map(ResolveResult::type)
                .findFirst();
    }

    private static Stream<ResolveResult> getBranches(CompoundType type, Map<String, TypeDefinition> definitionMap) {
        Map<String, TypeDefinition> definitions = updateDefinitionMapFromType(definitionMap, type);
        return type.getTypes().stream().map(t -> resolveType(t, definitions)).flatMap(
                resolveResult -> resolveResult.type() instanceof CompoundType compoundType
                        ? getBranches(compoundType, resolveResult.definitionMap())
                        : Stream.of(resolveResult)
        );
    }

    private static boolean hasAttribute(DataType type, String attributeId, Map<String, TypeDefinition> definitionMap) {
        ResolveResult resolveResult = resolveType(type, definitionMap);
        return ((resolveResult.type() instanceof ObjectType objectType)
                        && objectType.getSchema().getAttributes().stream().map(Attribute::getId).anyMatch(attributeId::equals))
                || ((resolveResult.type() instanceof ArrayType arrayType)
                        && hasAttribute(arrayType.getItemType(), attributeId, updateDefinitionMapFromType(resolveResult.definitionMap(), arrayType)))
                || ((resolveResult.type() instanceof CompoundType compoundType)
                        && findBranchByAttributeId(compoundType, attributeId, updateDefinitionMapFromType(resolveResult.definitionMap(), compoundType)).isPresent());
    }
}
