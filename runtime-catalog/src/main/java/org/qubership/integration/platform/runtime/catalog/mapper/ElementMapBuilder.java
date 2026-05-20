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
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Element;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class ElementMapBuilder {
    public record ElementContext(Map<String, TypeDefinition> definitionMap) {}

    public record ElementWithContext(
            Element element,
            ElementContext context
    ) {}

    public Map<String, ElementWithContext> buildElementMap(DataType type) {
        return buildElementMap(type, Collections.emptyMap());
    }

    public Map<String, ElementWithContext> buildElementMap(
            DataType type,
            Map<String, TypeDefinition> definitionMap
    ) {
        Map<String, ElementWithContext> elementMap = new HashMap<>();
        recursivelyProcessElements(
                type,
                definitionMap,
                elementWithContext -> elementMap.put(
                        elementWithContext.element().getId(), elementWithContext),
                element -> elementMap.containsKey(element.getId())
        );
        return elementMap;
    }

    private void recursivelyProcessElements(
            DataType type,
            Map<String, TypeDefinition> definitionMap,
            Consumer<ElementWithContext> consumer,
            Predicate<Element> checkIfElementProcessed
    ) {
        getAttributes(type, definitionMap)
                .filter(i -> !checkIfElementProcessed.test(i.element()))
                .forEach(i -> {
                    consumer.accept(i);
                    recursivelyProcessElements(
                            i.element().getType(),
                            i.context().definitionMap(),
                            consumer,
                            checkIfElementProcessed
                    );
                });
    }

    private Stream<ElementWithContext> getAttributes(
            DataType dataType,
            Map<String, TypeDefinition> definitionMap
    ) {
        DataTypeUtils.ResolveResult result = DataTypeUtils.resolveType(dataType, definitionMap);
        dataType = result.type();
        Map<String, TypeDefinition> definitions = DataTypeUtils.updateDefinitionMapFromType(
                definitionMap, dataType);
        if (dataType instanceof ArrayType type) {
            return getAttributes(type.getItemType(), definitions);
        } else if (dataType instanceof ObjectType type) {
            return type.getSchema().getAttributes().stream().map(attribute ->
                    new ElementWithContext(attribute, new ElementContext(definitions)));
        } else if (dataType instanceof CompoundType type) {
            return type.getTypes().stream().flatMap(t -> getAttributes(t, definitions));
        } else {
            return Stream.empty();
        }
    }
}
