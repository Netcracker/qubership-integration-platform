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

package org.qubership.integration.platform.mapper.build;

import org.qubership.integration.platform.mapper.model.mapping.definition.Attribute;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

public class DataTypeUtils {
    public record ResolveResult(org.qubership.integration.platform.mapper.model.datatypes.DataType type, Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitionMap) {}

    public static ResolveResult resolveType(org.qubership.integration.platform.mapper.model.datatypes.DataType type, Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitionMap) {
        while (type instanceof org.qubership.integration.platform.mapper.model.datatypes.ReferenceType referenceType) {
            definitionMap = updateDefinitionMapFromType(definitionMap, referenceType);
            String id = referenceType.getDefinitionId();
            org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition definition = definitionMap.get(id);
            if (isNull(definition)) {
                String message = String.format("Failed to resolve type definition with id: %s.", id);
                throw new MapperException(message);
            }
            type = definition.getType();
        }
        return new ResolveResult(type, definitionMap);
    }

    public static Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> updateDefinitionMapFromType(
            Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitionMap,
            org.qubership.integration.platform.mapper.model.datatypes.DataType dataType
    ) {
        if (dataType instanceof org.qubership.integration.platform.mapper.model.datatypes.TypeWithDefinitions type && !type.getDefinitions().isEmpty()) {
            Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> result = new HashMap<>(definitionMap);
            type.getDefinitions().forEach(definition -> result.put(definition.getId(), definition));
            return result;
        } else {
            return definitionMap;
        }
    }

    public static Optional<org.qubership.integration.platform.mapper.model.datatypes.DataType> findBranchByAttributeId(
            org.qubership.integration.platform.mapper.model.datatypes.CompoundType type,
            String attributeId,
            Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitionMap
    ) {
        return getBranches(type, definitionMap)
                .filter(resolveResult -> resolveResult.type() instanceof org.qubership.integration.platform.mapper.model.datatypes.ComplexType)
                .filter(resolveResult -> hasAttribute(resolveResult.type(), attributeId, resolveResult.definitionMap()))
                .map(ResolveResult::type)
                .findFirst();
    }

    private static Stream<ResolveResult> getBranches(org.qubership.integration.platform.mapper.model.datatypes.CompoundType type, Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitionMap) {
        Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitions = updateDefinitionMapFromType(definitionMap, type);
        return type.getTypes().stream().map(t -> resolveType(t, definitions)).flatMap(
                resolveResult -> resolveResult.type() instanceof org.qubership.integration.platform.mapper.model.datatypes.CompoundType compoundType
                        ? getBranches(compoundType, resolveResult.definitionMap())
                        : Stream.of(resolveResult)
        );
    }

    private static boolean hasAttribute(org.qubership.integration.platform.mapper.model.datatypes.DataType type, String attributeId, Map<String, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition> definitionMap) {
        ResolveResult resolveResult = resolveType(type, definitionMap);
        return ((resolveResult.type() instanceof org.qubership.integration.platform.mapper.model.datatypes.ObjectType objectType)
                        && objectType.getSchema().getAttributes().stream().map(Attribute::getId).anyMatch(attributeId::equals))
                || ((resolveResult.type() instanceof org.qubership.integration.platform.mapper.model.datatypes.ArrayType arrayType)
                        && hasAttribute(arrayType.getItemType(), attributeId, updateDefinitionMapFromType(resolveResult.definitionMap(), arrayType)))
                || ((resolveResult.type() instanceof org.qubership.integration.platform.mapper.model.datatypes.CompoundType compoundType)
                        && findBranchByAttributeId(compoundType, attributeId, updateDefinitionMapFromType(resolveResult.definitionMap(), compoundType)).isPresent());
    }
}
