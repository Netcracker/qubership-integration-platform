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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Chain bulk deploy request object")
public class BulkDeploymentRequest {
    @Schema(description = "List of domains to deploy to (usually \"default\")")
    private List<@NotBlank @Pattern(regexp = "^[-._a-zA-Z0-9]+$") String> domains;
    @Builder.Default
    @Schema(description = "Which snapshot should be taken during bulk deploy")
    private BulkDeploymentSnapshotAction snapshotAction = BulkDeploymentSnapshotAction.CREATE_NEW;
    @Builder.Default
    @Schema(description = "List of id of chains which should be (re)deployed")
    private List<@NotBlank @Pattern(regexp = "^[-._a-zA-Z0-9]+$") String> chainIds = new ArrayList<>();
}
