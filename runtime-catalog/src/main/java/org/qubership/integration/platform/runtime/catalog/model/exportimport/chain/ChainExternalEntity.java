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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.BaseExternalEntity;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequestAction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@SuperBuilder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChainExternalEntity extends BaseExternalEntity {

    private String businessDescription;
    private String assumptions;
    private String outOfScope;
    private String lastImportHash;

    private List<String> labels;
    private boolean maskingEnabled;
    @Builder.Default
    private Set<MaskedFieldExternalEntity> maskedFields = new HashSet<>();
    @JsonProperty("default-swimlane-id")
    private String defaultSwimlaneId;
    @JsonProperty("reuse-swimlane-id")
    private String reuseSwimlaneId;
    @Builder.Default
    private List<ChainElementExternalEntity> elements = new ArrayList<>();
    @Builder.Default
    private List<DependencyExternalEntity> dependencies = new ArrayList<>();
    private FolderExternalEntity folder;
    @Builder.Default
    private List<DeploymentExternalEntity> deployments = new ArrayList<>();
    private ChainCommitRequestAction deployAction;
    private Integer fileVersion;
    @JsonIgnore
    private boolean overridden;
    @JsonIgnore
    private String overridesChainId;
    @JsonIgnore
    private String overriddenByChainId;
}
