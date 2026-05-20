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
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.ObjectWithMetadata;

import java.util.List;

public class MappingAction extends ObjectWithMetadata {
    @JsonProperty(value = "id", required = true)
    private final String id;
    @JsonProperty(value = "sources", required = true)
    private final List<ElementReference> sources;
    @JsonProperty(value = "target", required = true)
    private final AttributeReference target;
    @JsonProperty("transformation")
    private final Transformation transformation;

    @JsonCreator
    public MappingAction(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "sources", required = true) List<ElementReference> sources,
            @JsonProperty(value = "target", required = true) AttributeReference target,
            @JsonProperty("transformation") Transformation transformation,
            @JsonProperty("metadata") Metadata metadata
    ) {
        super(metadata);
        this.id = id;
        this.sources = sources;
        this.target = target;
        this.transformation = transformation;
    }

    public String getId() {
        return id;
    }

    public List<ElementReference> getSources() {
        return sources;
    }

    public AttributeReference getTarget() {
        return target;
    }

    public Transformation getTransformation() {
        return transformation;
    }
}
