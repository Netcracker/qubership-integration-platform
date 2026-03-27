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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.DeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.logging.properties.ChainLoggingPropertiesSet;

import java.util.List;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Folder item response object")
public class FolderItemResponse extends BaseResponse {

    @Schema(description = "Parent (folder) id (if any)")
    private String parentId;

    @Schema(description = "Item type")
    private ItemType itemType;

    @Schema(description = "Assigned labels")
    private List<ChainLabelDTO> labels;

    @Schema(description = "Chain deployments list")
    private List<DeploymentResponse> deployments;

    @Schema(description = "Applied chain deployment properties")
    private ChainLoggingPropertiesSet chainRuntimeProperties;

    @Schema(description = "'Business description' for chain documentation")
    private String businessDescription;

    @Schema(description = "'Assumptions' for chain documentation")
    private String assumptions;

    @Schema(description = "'Out of scope' for chain documentation")
    private String outOfScope;

    private String overriddenByChainId;

    private String overriddenByChainName;

    private String overridesChainId;

    private String overridesChainName;
}
