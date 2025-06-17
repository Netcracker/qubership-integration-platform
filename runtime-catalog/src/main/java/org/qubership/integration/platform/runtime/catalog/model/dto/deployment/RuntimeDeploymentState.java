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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.DeploymentStatus;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Deployment status on particular engine pod")
public class RuntimeDeploymentState {
    @Schema(description = "Deployment status")
    private DeploymentStatus status;
    @Schema(description = "Error message (if any)")
    private String error;
    @Schema(description = "Stacktrace of error (if any)")
    private String stacktrace;
    @Schema(description = "Whether deployment is waiting for pod initialization")
    private boolean suspended;
}
