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
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentDefaultParameters;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ParserUtils {

    private final ObjectMapper jsonMapper;

    @Autowired
    public ParserUtils(@Qualifier("primaryObjectMapper") ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
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
