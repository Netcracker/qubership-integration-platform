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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.chain.model.ContextService;
import org.qubership.integration.platform.io.readers.system.ContextServiceReader;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ContextServiceDtoMapper;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ContextServiceDeserializer}: it reads the exported context service through the
 * library reader and maps the result to the persistence entity.
 */
@ExtendWith(MockitoExtension.class)
class ContextServiceDeserializerTest {

    @Mock
    private ContextServiceReader contextServiceReader;
    @Mock
    private ContextServiceDtoMapper contextServiceDtoMapper;

    @Test
    void deserializeSystemReadsTheServiceAndMapsItToTheEntity() {
        File serviceFile = new File("context-service.yaml");
        ContextService contextService = mock(ContextService.class);
        ContextSystem contextSystem = new ContextSystem();
        when(contextServiceReader.read(serviceFile)).thenReturn(contextService);
        when(contextServiceDtoMapper.toInternalEntity(contextService)).thenReturn(contextSystem);

        ContextServiceDeserializer deserializer =
                new ContextServiceDeserializer(contextServiceReader, contextServiceDtoMapper);

        assertThat(deserializer.deserializeSystem(serviceFile)).isSameAs(contextSystem);
        verify(contextServiceReader).read(serviceFile);
    }
}
