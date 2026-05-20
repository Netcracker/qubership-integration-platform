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
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.BaseRequest;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Deployment request object")
public class DeploymentRequest extends BaseRequest {
    @Schema(description = "Domain which was used to deploy to, usually \"default\"")
    @Pattern(regexp = "^[-._a-zA-Z0-9]+$", message = "Invalid domain format")
    private String domain;
    @Schema(description = "Snapshot id")
    @Pattern(regexp = "^[-._a-zA-Z0-9]+$", message = "Invalid snapshotId format")
    private String snapshotId;
    @Deprecated
    @Schema(description = "Not used")
    private Boolean suspended;
}
