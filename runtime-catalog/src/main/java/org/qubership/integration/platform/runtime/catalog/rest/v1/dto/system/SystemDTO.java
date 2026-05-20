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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.model.dto.user.UserDTO;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;

import java.util.List;

@Data
@Schema(description = "Service")
public class SystemDTO {
    @Schema(description = "Id")
    private String id;
    @Schema(description = "Name")
    private String name;
    @Schema(description = "Service type")
    private IntegrationSystemType type;
    @Schema(description = "Description")
    private String description;
    @Schema(description = "Id of currently activated environment")
    private String activeEnvironmentId;
    @Schema(description = "k8s network service name (if it is discovered service)")
    private String internalServiceName;
    @Schema(description = "Protocol")
    private String protocol;
    @Schema(description = "Extended protocol (sub-type)")
    private String extendedProtocol;
    @Schema(description = "Protocol")
    private String specification;
    @Schema(description = "Labels assigned to the service")
    private List<SystemLabelDTO> labels;
    @Schema(description = "Timestamp of creation date")
    private Long createdWhen;
    @Schema(description = "User who created entity")
    private UserDTO createdBy;
    @Schema(description = "Timestamp of last modification date")
    private Long modifiedWhen;
    @Schema(description = "User who last modified entity")
    private UserDTO modifiedBy;
}
