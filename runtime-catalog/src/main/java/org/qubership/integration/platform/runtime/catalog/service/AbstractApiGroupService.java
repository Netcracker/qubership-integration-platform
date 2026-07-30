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

package org.qubership.integration.platform.runtime.catalog.service;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.ApiGroupLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.ApiGroupRepository;
import org.springframework.lang.Nullable;

public abstract class AbstractApiGroupService {

    public static final String SPECIFICATION_GROUP_NAME_ERROR_MESSAGE = "Specification group name is not unique";
    public static final String SYSTEM_NOT_FOUND_ERROR_MESSAGE = "Can't find system with given id";

    public static final String SPECIFICATION_GROUP_ID_SEPARATOR = "-";

    protected final ApiGroupRepository apiGroupRepository;
    protected final ActionsLogService actionLogger;
    protected final ApiGroupLabelsRepository apiGroupLabelsRepository;

    protected AbstractApiGroupService(
            ApiGroupRepository apiGroupRepository,
            ActionsLogService actionLogger,
            ApiGroupLabelsRepository apiGroupLabelsRepository
    ) {
        this.apiGroupRepository = apiGroupRepository;
        this.actionLogger = actionLogger;
        this.apiGroupLabelsRepository = apiGroupLabelsRepository;
    }

    @Nullable
    public ApiGroup getById(String id) {
        return apiGroupRepository.findById(id).orElse(null);
    }

    public String buildSpecificationGroupId(IntegrationSystem system, String name) {
        return system.getId() + SPECIFICATION_GROUP_ID_SEPARATOR + name;
    }

    protected void logSpecGroupAction(ApiGroup group, IntegrationSystem system, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.API_GROUP)
                .entityId(group.getId())
                .entityName(group.getName())
                .parentId(system == null ? null : system.getId())
                .parentName(system == null ? null : system.getName())
                .parentType(system == null ? null : EntityType.getSystemType(system))
                .operation(operation)
                .build());
    }
}
