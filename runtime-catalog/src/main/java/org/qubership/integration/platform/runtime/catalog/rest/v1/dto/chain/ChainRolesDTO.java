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
import lombok.*;
import org.qubership.integration.platform.catalog.model.deployment.engine.DeploymentStatus;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Chain roles object")
public class ChainRolesDTO {
    @Schema(description = "Chain id")
    private String chainId;
    @Schema(description = "Chain name")
    private String chainName;
    @Schema(description = "Element id")
    private String elementId;
    @Schema(description = "Element name")
    private String elementName;
    @Schema(description = "List of chain deployment statuses")
    private Collection<DeploymentStatus> deploymentStatus;
    @Schema(description = "Whether changes on graph is unsaved in the chain")
    private boolean unsavedChanges;
    @Builder.Default
    @Schema(description = "Element properties map")
    private Map<String, Object> properties = new LinkedHashMap<>();
    @Schema(description = "Timestamp of last modification date")
    private Long modifiedWhen;
}
