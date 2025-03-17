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

package org.qubership.integration.platform.runtime.catalog.model.mapper.mapping;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.action.MappingAction;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.MessageSchema;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.Constant;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.Metadata;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.ObjectWithMetadata;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.isNull;


public class MappingDescription extends ObjectWithMetadata {
    @JsonProperty(value = "source", required = true)
    private MessageSchema source;
    @JsonProperty(value = "target", required = true)
    private MessageSchema target;
    @JsonProperty(value = "constants", required = true)
    private Collection<Constant> constants;
    @JsonProperty(value = "actions", required = true)
    private Collection<MappingAction> actions;

    @JsonCreator
    public MappingDescription(
            @JsonProperty(value = "source", required = true) MessageSchema source,
            @JsonProperty(value = "target", required = true) MessageSchema target,
            @JsonProperty(value = "constants", required = true) Collection<Constant> constants,
            @JsonProperty(value = "actions", required = true) List<MappingAction> actions,
            @JsonProperty("metadata") Metadata metadata
    ) {
        super(metadata);
        this.source = isNull(source) ? new MessageSchema(null, null, null, null) : source;
        this.target = isNull(target) ? new MessageSchema(null, null, null, null) : target;
        this.constants = isNull(constants) ? Collections.emptyList() : constants;
        this.actions = isNull(actions) ? Collections.emptyList() : actions;
    }

    public MessageSchema getSource() {
        return source;
    }

    public MessageSchema getTarget() {
        return target;
    }

    public Collection<Constant> getConstants() {
        return constants;
    }

    public Collection<MappingAction> getActions() {
        return actions;
    }
}
