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

package org.qubership.integration.platform.runtime.catalog.model.dto.actionlog;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Action log single entity")
public class ActionLogDTO {
    @Schema(description = "Id")
    private String id;
    @Schema(description = "Timestamp of action")
    private long actionTime;
    private EntityType entityType;
    @Schema(description = "Entity id")
    private String entityId;
    @Schema(description = "Entity name")
    private String entityName;
    @Schema(description = "Parent entity id")
    private String parentId;
    private EntityType parentType;
    @Schema(description = "Parent entity name")
    private String parentName;
    @Schema(description = "Request id with which request was initialized")
    private String requestId;
    private LogOperation operation;
    @Schema(description = "Id of user who triggered action")
    private String userId;
    @Schema(description = "Name of user who triggered action")
    private String username;
}
