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

package org.qubership.integration.platform.runtime.catalog.model.mapper.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.qubership.integration.platform.runtime.catalog.model.dto.actionlog.ActionLogDTO;
import org.qubership.integration.platform.runtime.catalog.model.dto.actionlog.ActionLogResponse;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;

@Mapper(componentModel = "spring",
        uses = {
            MapperUtils.class,
        }
)
public interface ActionsLogMapper {

    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.username", target = "username")
    ActionLogDTO asDTO(ActionLog action);

    List<ActionLogDTO> asDTO(List<ActionLog> actions);

    @Mapping(source = "recordsAfterRange", target = "recordsAfterRange")
    @Mapping(source = "actionLogs", target = "actionLogs")
    ActionLogResponse asResponse(Long recordsAfterRange, List<ActionLog> actionLogs);
}
