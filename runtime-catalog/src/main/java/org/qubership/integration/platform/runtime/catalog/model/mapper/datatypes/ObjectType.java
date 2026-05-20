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
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.ObjectSchema;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.Metadata;

import java.util.Collection;
import java.util.Collections;

import static java.util.Objects.isNull;

public class ObjectType extends ComplexType {
    @JsonProperty(value = "schema", required = true)
    private final ObjectSchema schema;

    @JsonCreator
    public ObjectType(
            @JsonProperty(value = "schema", required = true) ObjectSchema schema,
            @JsonProperty("definitions") Collection<TypeDefinition> definitions,
            @JsonProperty("metadata") Metadata metadata
    ) {
        super(TypeKind.OBJECT, definitions, metadata);
        this.schema = isNull(schema) ? new ObjectSchema(null, null, null) : schema;
    }

    public ObjectSchema getSchema() {
        return schema;
    }

    @Override
    public <R, A, E extends Exception> R accept(DataTypeVisitor<R, A, E> visitor, A arg) throws E {
        return visitor.visitObjectType(this, arg);
    }

    @Override
    public Collection<Attribute> getNestedAttributes() {
        return this.getSchema().getAttributes().isEmpty()
                ? Collections.emptyList()
                : this.getSchema().getAttributes();
    }
}
