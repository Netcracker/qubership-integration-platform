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

package org.qubership.integration.platform.runtime.catalog.service.parsers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarVersionException;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentDefaultParameters;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ParserUtils {

    private final SystemModelBaseService systemModelBaseService;
    private final ObjectMapper jsonMapper;

    @Autowired
    public ParserUtils(SystemModelBaseService systemModelBaseService,
                       @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper) {
        this.systemModelBaseService = systemModelBaseService;
        this.jsonMapper = jsonMapper;
    }

    public String defineVersionName(SpecificationGroup specificationGroup, Object specificationObject) {
        return checkSimilarVersions(specificationGroup.getId(), defineVersion(specificationGroup, specificationObject));
    }

    public String defineVersion(SpecificationGroup specificationGroup, Object specificationObject) {
        if (specificationObject instanceof AsyncapiSpecification asyncSpec) {
            return defineVersion(specificationGroup, asyncSpec.getInfo().getVersion());
        }
        if (specificationObject instanceof OpenAPI openapiSpec) {
            return defineVersion(specificationGroup, openapiSpec.getInfo().getVersion());
        }
        if (specificationObject instanceof String stringSpec) {
            return defineVersion(specificationGroup, stringSpec);
        }
        // anyway just generate incrementing versions (1.0.0, 2.0.0, ...)
        return defineVersion(specificationGroup, null);
    }

    private String defineVersion(SpecificationGroup specificationGroup, String version) {
        return version == null ? generateVersion(specificationGroup.getId()) : version;
    }

    private String checkSimilarVersions(String specificationGroupId, String version) {
        long count = systemModelBaseService.countBySpecificationGroupIdAndVersion(specificationGroupId, version);
        if (count > 0) {
            throw new SpecificationSimilarVersionException(version);
        }
        return version;
    }

    private String generateVersion(String id) {
        int count = systemModelBaseService.getSystemModelsBySpecificationGroupId(id).size() + 1;
        return count + ".0.0";
    }

    public JsonNode receiveEmptyProperties(OperationProtocol protocol) {
        try {
            return jsonMapper.readTree(
                    OperationProtocol.AMQP.equals(protocol)
                            ? jsonMapper.writeValueAsString(EnvironmentDefaultParameters.RABBIT_ENVIRONMENT_PARAMETERS)
                            : jsonMapper.writeValueAsString(EnvironmentDefaultParameters.KAFKA_ENVIRONMENT_PARAMETERS));
        } catch (JsonProcessingException e) {
            throw new SpecificationImportException("Error while receiving environment properties for " + protocol + " protocol", e);
        }
    }
}
