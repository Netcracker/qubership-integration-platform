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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.RuntimeDeploymentState;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Particular engine pod deployment state")
public class EngineDeploymentResponse {
    @Schema(description = "Id")
    private String id;
    @Schema(description = "Chain id")
    private String chainId;
    @Schema(description = "Chain name")
    private String chainName;
    @Schema(description = "Snapshot name")
    private String snapshotName;
    @Schema(description = "Deployment status")
    private RuntimeDeploymentState state;
}
