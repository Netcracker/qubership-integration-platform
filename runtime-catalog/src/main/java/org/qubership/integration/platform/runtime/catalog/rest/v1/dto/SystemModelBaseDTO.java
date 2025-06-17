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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;
import org.qubership.integration.platform.runtime.catalog.model.dto.user.UserDTO;

import java.util.List;

@Data
@Schema(description = "Specification")
public class SystemModelBaseDTO {
    @Schema(description = "id")
    private String id;
    @Schema(description = "Description")
    private String description;
    @Schema(description = "Name (usually same as version)")
    private String name;
    @Schema(description = "Specification group id")
    private String specificationGroupId;
    @Schema(description = "Whether specification is active")
    private Boolean active;
    @Schema(description = "Whether specification is deprecated")
    private Boolean deprecated;
    @Schema(description = "Specification version")
    private String version;
    @Schema(description = "Raw contents of specification source file")
    private String source;
    @Schema(description = "Service id")
    private String systemId;
    @Schema(description = "Timestamp of object creation")
    private Long createdWhen;
    @Schema(description = "User who created that object")
    private UserDTO createdBy;
    @Schema(description = "Timestamp of object last modification")
    private Long modifiedWhen;
    @Schema(description = "User who last modified that object")
    private UserDTO modifiedBy;
    @Schema(description = "List of chains that is using current specification")
    private List<BaseResponse> chains;
    @Schema(description = "Labels assigned to the specification")
    private List<SystemModelLabelDTO> labels;
}
