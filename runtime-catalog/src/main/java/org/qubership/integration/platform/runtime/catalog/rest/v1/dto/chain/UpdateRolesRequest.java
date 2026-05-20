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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Http trigger roles update request object")
public class UpdateRolesRequest {
    @Schema(description = "Element id")
    private String elementId;
    @Schema(description = "List of roles")
    private Set<String> roles;
    @Schema(description = "Whether chain redeploy should be initiated")
    private Boolean isRedeploy;
    @Schema(description = "Chain id")
    private String chainId;
    @Schema(description = "Whether changes on graph is unsaved in the chain")
    private Boolean unsavedChanges;

    public UpdateRolesRequest(boolean unsavedChanges, String id) {
        this.unsavedChanges = unsavedChanges;
        this.chainId = id;
    }
}
