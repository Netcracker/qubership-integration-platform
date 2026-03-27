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

package org.qubership.integration.platform.runtime.catalog.model.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.qubership.integration.platform.runtime.catalog.model.dto.user.UserDTO;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response object for a single chain deployment")
public class DeploymentResponse {
    @Schema(description = "Inner deployment id")
    private String id;
    @Schema(description = "Chain id")
    private String chainId;
    @Schema(description = "Snapshot id")
    private String snapshotId;
    @Schema(description = "Deployment name, usually V<number>")
    private String name;
    @Schema(description = "Domain which was used to deploy to, usually \"default\"")
    private String domain;
    @Schema(description = "Timestamp of object creation")
    private Long createdWhen;
    @Schema(description = "User who created that object")
    private UserDTO createdBy;
    @Deprecated
    @Schema(description = "Not used")
    private boolean suspended;
    @Schema(description = "Deployment runtime status (divided by engine pods)")
    private DeploymentRuntime runtime;
    @Schema(description = "Service name to display for errors on ui")
    private String serviceName;
}
