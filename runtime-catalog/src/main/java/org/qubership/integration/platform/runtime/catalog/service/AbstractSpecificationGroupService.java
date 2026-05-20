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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.springframework.lang.Nullable;

public abstract class AbstractSpecificationGroupService {

    public static final String SPECIFICATION_GROUP_NAME_ERROR_MESSAGE = "Specification group name is not unique";
    public static final String SYSTEM_NOT_FOUND_ERROR_MESSAGE = "Can't find system with given id";

    public static final String SPECIFICATION_GROUP_ID_SEPARATOR = "-";

    protected final SpecificationGroupRepository specificationGroupRepository;
    protected final ActionsLogService actionLogger;
    protected final SpecificationGroupLabelsRepository specificationGroupLabelsRepository;

    public AbstractSpecificationGroupService(
            SpecificationGroupRepository specificationGroupRepository,
            ActionsLogService actionLogger,
            SpecificationGroupLabelsRepository specificationGroupLabelsRepository
    ) {
        this.specificationGroupRepository = specificationGroupRepository;
        this.actionLogger = actionLogger;
        this.specificationGroupLabelsRepository = specificationGroupLabelsRepository;
    }

    @Nullable
    public SpecificationGroup getById(String id) {
        return specificationGroupRepository.findById(id).orElse(null);
    }

    public String buildSpecificationGroupId(IntegrationSystem system, String name) {
        return system.getId() + SPECIFICATION_GROUP_ID_SEPARATOR + name;
    }

    protected void logSpecGroupAction(SpecificationGroup group, IntegrationSystem system, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SPECIFICATION_GROUP)
                .entityId(group.getId())
                .entityName(group.getName())
                .parentId(system == null ? null : system.getId())
                .parentName(system == null ? null : system.getName())
                .parentType(system == null ? null : EntityType.getSystemType(system))
                .operation(operation)
                .build());
    }
}
