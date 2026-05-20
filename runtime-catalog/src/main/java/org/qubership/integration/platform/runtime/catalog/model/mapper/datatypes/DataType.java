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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Attribute;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.MetadataAware;

import java.util.Collection;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "name")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NullType.class, name = "null"),
        @JsonSubTypes.Type(value = StringType.class, name = "string"),
        @JsonSubTypes.Type(value = IntegerType.class, name = "number"),
        @JsonSubTypes.Type(value = BooleanType.class, name = "boolean"),
        @JsonSubTypes.Type(value = ArrayType.class, name = "array"),
        @JsonSubTypes.Type(value = ObjectType.class, name = "object"),
        @JsonSubTypes.Type(value = ReferenceType.class, name = "reference"),
        @JsonSubTypes.Type(value = AllOfType.class, name = "allOf"),
        @JsonSubTypes.Type(value = AnyOfType.class, name = "anyOf"),
        @JsonSubTypes.Type(value = OneOfType.class, name = "oneOf")
})
public interface DataType extends MetadataAware {
    TypeKind getKind();

    <R, A, E extends Exception> R accept(DataTypeVisitor<R, A, E> visitor, A arg) throws E;

    Collection<Attribute> getNestedAttributes();
}
