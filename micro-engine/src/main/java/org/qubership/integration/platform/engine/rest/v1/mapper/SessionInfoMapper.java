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

package org.qubership.integration.platform.engine.rest.v1.mapper;

import org.mapstruct.Mapper;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.rest.v1.dto.checkpoint.CheckpointSessionDTO;
import org.qubership.integration.platform.engine.util.MapperUtils;

import java.util.List;

@Mapper(componentModel = "spring",
    uses = {
        MapperUtils.class,
        CheckpointMapper.class
    }
)
public interface SessionInfoMapper {

    CheckpointSessionDTO asDTO(SessionInfo sessionInfo);

    List<CheckpointSessionDTO> asDTO(List<SessionInfo> sessionInfo);

}
