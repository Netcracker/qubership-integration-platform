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

package org.qubership.integration.platform.runtime.catalog.model.exportimport.chain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.qubership.integration.platform.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.BaseExternalEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@SuperBuilder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChainElementExternalEntity extends BaseExternalEntity {

    @JsonProperty("element-type")
    private String type;
    @JsonProperty("swimlane-id")
    private String swimlaneId;
    @Builder.Default
    private List<ChainElementExternalEntity> children = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();
    @JsonProperty("original-id")
    private String originalId;
    @JsonProperty("service-environment")
    private ServiceEnvironment serviceEnvironment;
    @JsonProperty("properties-filename")
    private String propertiesFilename;
}
