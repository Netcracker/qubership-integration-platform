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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.DeploymentExternalEntity;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequestAction;

import java.util.List;
import java.util.Set;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Chain preview import result")
public class ImportChainPreviewDTO {
    @Schema(description = "Id")
    private String id;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "List of services used in the chain")
    private Set<String> usedSystems;

    @Schema(description = "Action that will be applied by default")
    private ChainCommitRequestAction deployAction;

    @Schema(description = "Deployment info (if action = deploy)")
    private List<DeploymentExternalEntity> deployments;

    @Schema(description = "Import instruction action to be applied to chain")
    private ImportInstructionAction instructionAction;

    @Schema(description = "Error or warning message (if any)")
    private String errorMessage;

    @Schema(description = "Indicates whether this chain exists")
    private Boolean exists;
}
