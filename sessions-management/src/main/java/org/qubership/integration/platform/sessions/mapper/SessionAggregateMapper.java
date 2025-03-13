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

package org.qubership.integration.platform.sessions.mapper;

import org.mapstruct.*;
import org.qubership.integration.platform.sessions.dto.Session;
import org.qubership.integration.platform.sessions.dto.opensearch.SessionElementElastic;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Mapper(componentModel = "spring", uses = SessionElementMapper.class, builder = @Builder(disableBuilder = true))
public abstract class SessionAggregateMapper {

    public Session toPreview(SessionElementElastic element) {
        return toPreview(element, new Session());
    }

    public abstract List<Session> toPreview(List<SessionElementElastic> elements);

    @Mapping(target = "id", source = "element.sessionId")
    @Mapping(target = "externalSessionCipId", source = "element.externalSessionId")
    @Mapping(target = "started", source = "element.sessionStarted")
    @Mapping(target = "finished", source = "element.sessionFinished")
    @Mapping(target = "duration", source = "element.sessionDuration")
    @Mapping(target = "executionStatus", source = "element.sessionExecutionStatus")
    @Mapping(target = "parentSessionId", source = "element.parentSessionId")
    protected abstract Session toPreview(SessionElementElastic element, @MappingTarget Session session);

    @Mapping(target = "sessionElements", source = "elements")
    protected abstract Session toSession(SessionElementElastic element, List<SessionElementElastic> elements);

    public Session toSession(List<SessionElementElastic> elements, boolean includeElements) {
        if (CollectionUtils.isEmpty(elements)) {
            return null;
        }
        return includeElements ? toSession(elements.get(0), elements) : toPreview(elements.get(0));
    }

    @AfterMapping
    protected void fillSession(SessionElementElastic element, List<SessionElementElastic> elements, @MappingTarget Session session) {
        toPreview(element, session);
    }
}

