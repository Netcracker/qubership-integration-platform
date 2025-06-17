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

package org.qubership.integration.platform.runtime.catalog.testutils.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Jacksonized
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@SuperBuilder
public class ChainImportDTO extends ImportEntityDTO {

    private String businessDescription;
    private String assumptions;
    private String outOfScope;

    private final boolean maskingEnabled;
    private final Set<MaskedFieldImportDTO> maskedFields;
    @JsonProperty("default-swimlane-id")
    private final String defaultSwimlaneId;
    @JsonProperty("reuse-swimlane-id")
    private final String reuseSwimlaneId;
    private final List<ChainElementImportDTO> elements;
    private final List<DependencyImportDTO> dependencies;
    private final FolderImportDTO folder;

    public boolean isMaskingEnabled() {
        return maskingEnabled;
    }

    public Set<MaskedFieldImportDTO> getMaskedFields() {
        return maskedFields != null ? maskedFields : new HashSet<>();
    }

    public String getDefaultSwimlaneId() {
        return defaultSwimlaneId;
    }

    public String getReuseSwimlaneId() {
        return reuseSwimlaneId;
    }

    public List<ChainElementImportDTO> getElements() {
        return elements != null ? elements : new ArrayList<>();
    }

    public List<DependencyImportDTO> getDependencies() {
        return dependencies != null ? dependencies : new ArrayList<>();
    }

    public FolderImportDTO getFolder() {
        return folder;
    }

    public String getBusinessDescription() {
        return businessDescription;
    }

    public String getAssumptions() {
        return assumptions;
    }

    public String getOutOfScope() {
        return outOfScope;
    }
}
