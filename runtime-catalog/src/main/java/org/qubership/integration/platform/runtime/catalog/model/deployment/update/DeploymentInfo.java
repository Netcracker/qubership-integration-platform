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

package org.qubership.integration.platform.runtime.catalog.model.deployment.update;

import com.fasterxml.jackson.annotation.JsonFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import javax.annotation.Nullable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonFilter("DeploymentInfoFilter")
@Schema(description = "Information about particular deployment applied on engine pod")
public class DeploymentInfo {
    @Schema(description = "Deployment id")
    private String deploymentId;
    @Nullable
    @Schema(description = "Chain id")
    private String chainId;
    @Schema(description = "Chain name")
    private String chainName;
    @Schema(description = "Snapshot id")
    private String snapshotId;
    @Schema(description = "Snapshot name")
    private String snapshotName;
    @Schema(description = "Status code (in case of deployment failure)")
    private String chainStatusCode;
    @Schema(description = "Timestamp of object creation")
    private Long createdWhen;
    @Schema(description = "Whether chain contains checkpoint elements")
    private Boolean containsCheckpointElements;
    @Schema(description = "Whether chain contains scheduler elements")
    private Boolean containsSchedulerElements;
}
