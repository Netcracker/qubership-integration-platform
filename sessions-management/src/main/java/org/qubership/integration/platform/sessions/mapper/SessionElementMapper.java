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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.qubership.integration.platform.sessions.dto.Session;
import org.qubership.integration.platform.sessions.dto.SessionElement;
import org.qubership.integration.platform.sessions.dto.SessionElementProperty;
import org.qubership.integration.platform.sessions.dto.opensearch.SessionElementElastic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@Mapper(componentModel = "spring")
public abstract class SessionElementMapper {

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Mapping(target = "elementId", source = "element.id")
    @Mapping(target = "camelName", source = "element.camelElementName")
    @Mapping(target = "previousElement", source = "element.prevElementId")
    @Mapping(target = "parentElement", source = "element.parentElementId")
    @Mapping(target = "children", expression = "java(newList())")
    @Mapping(target = "headersBefore", expression = "java(convertFromJson(element.getHeadersBefore()))")
    @Mapping(target = "headersAfter", expression = "java(convertFromJson(element.getHeadersAfter()))")
    @Mapping(target = "propertiesBefore",
            expression = "java(convertPropertiesFromJson(element.getPropertiesBefore()))")
    @Mapping(target = "propertiesAfter",
            expression = "java(convertPropertiesFromJson(element.getPropertiesAfter()))")
    public abstract SessionElement toSessionElement(SessionElementElastic element);

    @Mapping(target = "id", source = "element.elementId")
    @Mapping(target = "camelElementName", source = "element.camelName")
    @Mapping(target = "prevElementId", source = "element.previousElement")
    @Mapping(target = "parentElementId", source = "element.parentElement")
    @Mapping(target = "started", source = "element.started")
    @Mapping(target = "finished", source = "element.finished")
    @Mapping(target = "duration", source = "element.duration")
    @Mapping(target = "executionStatus", source = "element.executionStatus")
    @Mapping(target = "sessionStarted", source = "session.started")
    @Mapping(target = "sessionFinished", source = "session.finished")
    @Mapping(target = "sessionDuration", source = "session.duration")
    @Mapping(target = "sessionExecutionStatus", source = "session.executionStatus")
    @Mapping(target = "headersBefore", expression = "java(convertMapToJson(element.getHeadersBefore()))")
    @Mapping(target = "headersAfter", expression = "java(convertMapToJson(element.getHeadersAfter()))")
    @Mapping(target = "propertiesBefore",
            expression = "java(convertSessionElementPropertyMapToJson(element.getPropertiesBefore()))")
    @Mapping(target = "propertiesAfter",
            expression = "java(convertSessionElementPropertyMapToJson(element.getPropertiesAfter()))")
    @Mapping(target = "chainElementId", expression = "java(null)")
    @Mapping(target = "chainName", source = "session.chainName")
    public abstract SessionElementElastic toSessionElementElastic(SessionElement element, Session session);

    public List<SessionElement> toSessionElements(List<SessionElementElastic> elasticElements) {
        List<SessionElement> sessionElementList = elasticElements.stream()
                .map(this::toSessionElement)
                .collect(Collectors.toList());

        return rebindElements(sessionElementList);
    }

    public List<SessionElementElastic> toElements(List<Session> sessions) {
        List<SessionElementElastic> result = new ArrayList<>();
        sessions.forEach(session -> {
            result.addAll(toElasticElements(session.getSessionElements(), session));
        });
        return result;
    }

    public List<SessionElementElastic> toElasticElements(List<SessionElement> elements, Session session) {
        List<SessionElementElastic> result = new ArrayList<>();
        if (!CollectionUtils.isEmpty(elements)) {
            for (SessionElement sessionElement : elements) {
                result.add(toSessionElementElastic(sessionElement, session));
                result.addAll(toElasticElements(sessionElement.getChildren(), session));
            }
        }
        return result;
    }

    private List<SessionElement> rebindElements(List<SessionElement> elementList) {
        Set<String> parentElementsIdList = elementList.stream()
                .map(SessionElement::getParentElement)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, SessionElement> parentElementMap = elementList.stream()
                .filter(element -> {
                    String elementId = element.getElementId();
                    return parentElementsIdList.contains(elementId);
                })
                .map(element -> {
                    element.setChildren(new ArrayList<>());
                    String elementId = element.getElementId();
                    return Map.entry(elementId, element);
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (element1, element2) -> {
                    log.warn("Duplicated session element: {}. Session {} data is inconsistent.",
                            element1.getElementId(), element1.getSessionId());
                    return element1;
                }));

        List<SessionElement> elementsHierarchy = elementList.stream()
                .filter(element -> {
                    String parentElementId = element.getParentElement();
                    if (parentElementId != null) {
                        SessionElement parentElement = parentElementMap.get(parentElementId);
                        if (parentElement != null) {
                            parentElement.getChildren().add(element);
                        } else {
                            // attach elements without real parent (but with parentId) to root
                            // useful in case of INFO/ERROR log levels (not all elements can be logged)
                            parentElementId = null;
                        }
                    }
                    return parentElementId == null;
                })
                .collect(Collectors.toList());

        sortChildrenByStartTime(elementsHierarchy);
        return elementsHierarchy;
    }

    private void sortChildrenByStartTime(List<SessionElement> elementList) {
        elementList.sort((first, second) -> {
            String startedFirstStr = first.getStarted();
            String startedSecondStr = second.getStarted();
            LocalDateTime dateFirst = LocalDateTime.parse(startedFirstStr);
            LocalDateTime dateSecond = LocalDateTime.parse(startedSecondStr);
            boolean isBefore = dateSecond.isBefore(dateFirst);
            return isBefore ? 1 : -1;
        });

        elementList.forEach(nextElement -> {
            List<SessionElement> childrenList = nextElement.getChildren();
            this.sortChildrenByStartTime(childrenList);
        });
    }

    protected Map<String, SessionElementProperty> convertPropertiesFromJson(String v2PropsJson) {
        try {
            if (StringUtils.isNotEmpty(v2PropsJson)) {
                return objectMapper.readValue(v2PropsJson, new TypeReference<>() {});
            }
        } catch (JsonProcessingException e) {
            log.error("Error while deserializing json string: {}", v2PropsJson);
        }

        return Collections.emptyMap();
    }

    protected Map<String, String> convertFromJson(String jsonString) {
        if (StringUtils.isBlank(jsonString)) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonString, new TypeReference<>(){});
        } catch (JsonProcessingException e) {
            log.error("Error while deserializing json string: {}", jsonString);
        }
        return null;
    }

    protected List newList() {
        return new ArrayList<>();
    }

    protected String convertMapToJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Error while serializing map to json: {}", map);
        }
        return null;
    }

    protected String convertSessionElementPropertyMapToJson(Map<String, SessionElementProperty> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Error while serializing map to json: {}", map);
        }
        return null;
    }

}
