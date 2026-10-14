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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.EnvironmentDTO;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.EnvironmentRequestDTO;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentDefaultParameters;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

@Mapper(componentModel = "spring", uses = { MapperUtils.class })
public abstract class EnvironmentMapper {
    @Autowired
    @Qualifier("primaryObjectMapper")
    ObjectMapper objectMapper;

    @Mapping(target = "systemId", source = "system.id")
    @Mapping(target = "defaultProperties", expression = "java(getDefaultProperties(environment))")
    @Mapping(target = "maasDefaultProperties", expression = "java(getMaasDefaultProperties(environment))")
    public abstract EnvironmentDTO toDTO(Environment environment);

    public abstract List<EnvironmentDTO> toDTOs(List<Environment> environments);

    public abstract Environment toEnvironment(EnvironmentRequestDTO environmentRequestDTO);

    public abstract void merge(EnvironmentRequestDTO environmentRequestDTO, @MappingTarget Environment environment);

    @SuppressWarnings("unused")
    protected JsonNode getDefaultProperties(Environment environment) {
        if (environment.getSystem() != null && environment.getSystem().getProtocol() != null) {
            switch (environment.getSystem().getProtocol()) {
                case HTTP -> {
                    return objectMapper.convertValue(EnvironmentDefaultParameters.HTTP_ENVIRONMENT_PARAMETERS, JsonNode.class);
                }
                case KAFKA -> {
                    return objectMapper.convertValue(EnvironmentDefaultParameters.KAFKA_ENVIRONMENT_PARAMETERS, JsonNode.class);
                }
                case AMQP -> {
                    return objectMapper.convertValue(EnvironmentDefaultParameters.RABBIT_ENVIRONMENT_PARAMETERS, JsonNode.class);
                }
            }
        }

        return objectMapper.createObjectNode();
    }

    @SuppressWarnings("unused")
    protected JsonNode getMaasDefaultProperties(Environment environment) {
        if (environment.getSystem() != null && environment.getSystem().getProtocol() != null) {
            switch (environment.getSystem().getProtocol()) {
                case KAFKA -> {
                    return objectMapper.convertValue(EnvironmentDefaultParameters.MAAS_BY_CLASSIFIER_KAFKA_ENVIRONMENT_PARAMETERS, JsonNode.class);
                }
                case AMQP -> {
                    return objectMapper.convertValue(EnvironmentDefaultParameters.MAAS_BY_CLASSIFIER_RABBIT_ENVIRONMENT_PARAMETERS, JsonNode.class);
                }
            }
        }

        return objectMapper.createObjectNode();
    }
}
