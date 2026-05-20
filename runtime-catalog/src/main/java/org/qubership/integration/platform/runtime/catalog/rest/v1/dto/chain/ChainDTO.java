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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;
import org.qubership.integration.platform.runtime.catalog.model.dto.dependency.DependencyResponse;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.DeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.ElementResponse;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Full (with elements) response object for a single chain")
public class ChainDTO extends BaseResponse {

    @Schema(description = "Navigation path through folders for the chain")
    private Map<String, String> navigationPath;

    @Schema(description = "Elements contained in that chain")
    private List<ElementResponse> elements;

    @Schema(description = "Dependencies (links) between elements of the chain")
    private List<DependencyResponse> dependencies;

    @Schema(description = "Deployments for the chain")
    private List<DeploymentResponse> deployments;

    @Schema(description = "Labels assigned to the chain")
    private List<ChainLabelDTO> labels;

    @Schema(description = "Id of default swimlane on chain graph (if any)")
    private String defaultSwimlaneId;

    @Schema(description = "Id of reuse swimlane on chain graph (if any)")
    private String reuseSwimlaneId;

    @Schema(description = "Parent id of the chain (folder id)")
    private String parentId;

    @Schema(description = "Currently applied snapshot for the chain")
    private BaseResponse currentSnapshot;

    @Schema(description = "Whether chain contains unsaved changes to a snapshot")
    private boolean unsavedChanges;

    @Schema(description = "'Business description' for chain documentation")
    private String businessDescription;

    @Schema(description = "'Assumptions' for chain documentation")
    private String assumptions;

    @Schema(description = "'Out of scope' for chain documentation")
    private String outOfScope;

    @Builder.Default
    @Schema(description = "Whether chain contains deprecated containers")
    private boolean containsDeprecatedContainers = false;

    @Builder.Default
    @Schema(description = "Whether chain contains deprecated elements")
    private boolean containsDeprecatedElements = false;

    @Builder.Default
    @Schema(description = "Whether chain contains unsupported (deleted) elements")
    private boolean containsUnsupportedElements = false;

    private String overriddenByChainId;

    private String overriddenByChainName;

    private String overridesChainId;

    private String overridesChainName;

}
