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
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.Metadata;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.ObjectWithMetadata;

import static java.util.Objects.isNull;

public abstract class Element extends ObjectWithMetadata {
    @JsonProperty(value = "id", required = true)
    private final String id;
    @JsonProperty(value = "name", required = true)
    private final String name;
    @JsonProperty(value = "type", required = true)
    private final DataType type;

    @JsonCreator
    public Element(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "type", required = true) DataType type,
            @JsonProperty("metadata") Metadata metadata
    ) {
        super(metadata);
        this.id = id;
        this.name = name;
        this.type = isNull(type) ? new NullType(null) : type;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public DataType getType() {
        return type;
    }
}
