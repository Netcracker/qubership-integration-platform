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

package org.qubership.integration.platform.runtime.catalog.model.dto.system;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.model.dto.user.UserDTO;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;

import java.util.List;

@Data
@Schema(description = "Environment for a Service")
public class EnvironmentDTO {
    @Schema(description = "Id")
    private String id;
    @Schema(description = "Service id")
    private String systemId;
    @Schema(description = "Name")
    private String name;
    @Schema(description = "Description")
    private String description;
    @Schema(description = "Address (if service type is not MaaS")
    private String address;
    @Schema(description = "List of assigned labels")
    private List<EnvironmentLabel> labels;
    @Schema(description = "Additional properties")
    private JsonNode properties;
    @Schema(description = "Default properties")
    private JsonNode defaultProperties;
    @Schema(description = "MaaS default properties")
    private JsonNode maasDefaultProperties;
    @Schema(description = "Timestamp of creation date")
    private Long createdWhen;
    @Schema(description = "User who created entity")
    private UserDTO createdBy;
    @Schema(description = "Timestamp of last modification date")
    private Long modifiedWhen;
    @Schema(description = "User who last modified entity")
    private UserDTO modifiedBy;
    @Schema(description = "Environment data source type")
    private EnvironmentSourceType sourceType;
    @Deprecated
    @Schema(description = "MaaS instance id for deprecated MaaS data source type")
    private String maasInstanceId;
}
