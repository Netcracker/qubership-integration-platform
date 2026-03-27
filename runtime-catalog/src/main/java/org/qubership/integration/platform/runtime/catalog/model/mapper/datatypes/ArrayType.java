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

package org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Attribute;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.Metadata;

import java.util.Collection;

import static java.util.Objects.isNull;


public class ArrayType extends ComplexType {
    @JsonProperty(value = "itemType", required = true)
    private final DataType itemType;

    @JsonCreator
    public ArrayType(
            @JsonProperty(value = "itemType", required = true) DataType itemType,
            @JsonProperty("definitions") Collection<TypeDefinition> definitions,
            @JsonProperty("metadata") Metadata metadata) {
        super(TypeKind.ARRAY, definitions, metadata);
        this.itemType = isNull(itemType) ? new NullType(null) : itemType;
    }

    public DataType getItemType() {
        return itemType;
    }

    @Override
    public <R, A, E extends Exception> R accept(DataTypeVisitor<R, A, E> visitor, A arg) throws E {
        return visitor.visitArrayType(this, arg);
    }

    @Override
    public Collection<Attribute> getNestedAttributes() {
        return this.itemType.getNestedAttributes();
    }
}
