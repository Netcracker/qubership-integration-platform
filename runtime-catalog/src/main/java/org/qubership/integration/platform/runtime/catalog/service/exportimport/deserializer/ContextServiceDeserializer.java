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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.chain.model.ContextService;
import org.qubership.integration.platform.io.readers.system.ContextServiceReader;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ContextServiceDtoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;


@Slf4j
@Component
public class ContextServiceDeserializer {

    private final ContextServiceReader contextServiceReader;
    private final ContextServiceDtoMapper contextServiceDtoMapper;

    @Autowired
    public ContextServiceDeserializer(ContextServiceReader contextServiceReader,
                                      ContextServiceDtoMapper contextServiceDtoMapper) {
        this.contextServiceReader = contextServiceReader;
        this.contextServiceDtoMapper = contextServiceDtoMapper;
    }

    public ContextSystem deserializeSystem(File serviceFile) {
        ContextService contextService = contextServiceReader.read(serviceFile);
        return contextServiceDtoMapper.toInternalEntity(contextService);
    }
}
