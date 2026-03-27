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

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;

import javax.annotation.Nullable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Routes to register on control-plane during deployment update")
public class DeploymentRouteUpdate {
    @Schema(description = "Uri")
    private String path;
    @Nullable
    @Schema(description = "gateway prefix for senders and service-calls")
    private String gatewayPrefix; // for senders and services
    @Nullable
    @Schema(description = "Variable name in xml to substitute with resolved path")
    private String variableName; // to substitute with resolved path
    @Schema(description = "Route type to register on control plane")
    private RouteType type;
    @Builder.Default
    @Schema(description = "Connection timeout")
    private Long connectTimeout = 120000L;
}

