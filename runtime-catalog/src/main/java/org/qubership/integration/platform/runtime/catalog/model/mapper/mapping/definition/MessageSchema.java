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

package org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.DataType;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.NullType;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.TypeDefinition;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.TypeWithDefinitions;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.Metadata;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.ObjectWithMetadata;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

public class MessageSchema extends ObjectWithMetadata {
    @JsonProperty(value = "headers", required = true)
    private Collection<Attribute> headers;
    @JsonProperty(value = "properties", required = true)
    private Collection<Attribute> properties;
    @JsonProperty("body")
    private DataType body;

    @JsonCreator
    public MessageSchema(
            @JsonProperty(value = "headers", required = true) Collection<Attribute> headers,
            @JsonProperty(value = "properties", required = true) Collection<Attribute> properties,
            @JsonProperty("body") DataType body,
            @JsonProperty("metadata") Metadata metadata
    ) {
        super(metadata);
        this.headers = isNull(headers) ? Collections.emptyList() : headers;
        this.properties = isNull(properties) ? Collections.emptyList() : properties;
        this.body = isNull(body) ? new NullType(null) : body;
    }

    public Collection<Attribute> getHeaders() {
        return headers;
    }

    public Collection<Attribute> getProperties() {
        return properties;
    }

    public DataType getBody() {
        return body;
    }

    public Map<String, DataType> getBodyDefinitions() {
        if (body instanceof TypeWithDefinitions) {
            return ((TypeWithDefinitions) body)
                    .getDefinitions()
                    .stream()
                    .collect(Collectors.toMap(TypeDefinition::getId, TypeDefinition::getType));
        }
        return Collections.emptyMap();
    }
}
