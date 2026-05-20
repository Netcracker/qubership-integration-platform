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

package org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.action;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.Metadata;

import java.util.Collections;
import java.util.List;

import static java.util.Objects.isNull;

public class AttributeReference extends ElementReference {

    @JsonProperty(value = "kind", required = true)
    private final AttributeKind kind;
    @JsonProperty(value = "path", required = true)
    private final List<String> path;

    @JsonCreator
    public AttributeReference(
            @JsonProperty(value = "kind", required = true) AttributeKind kind,
            @JsonProperty(value = "path", required = true) List<String> path,
            @JsonProperty("metadata") Metadata metadata
    ) {
        super(ElementType.ATTRIBUTE, metadata);
        this.kind = kind;
        this.path = isNull(path) ? Collections.emptyList() : path;
    }

    public AttributeKind getKind() {
        return kind;
    }

    public List<String> getPath() {
        return path;
    }
}
