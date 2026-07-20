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

import org.qubership.integration.platform.chain.model.ImportSpecificationGroup;
import org.qubership.integration.platform.chain.model.ImportSpecificationSource;
import org.qubership.integration.platform.chain.model.ImportSystem;
import org.qubership.integration.platform.chain.model.ImportSystemModel;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceImportException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SpecificationGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemEntitySeam;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Turns an exported integration-system archive into its persistence graph.
 *
 * <p>The library {@link IntegrationSystemReader} reads the whole archive — the system YAML, the
 * specification groups and system models in their separate files, and each specification source's
 * text — into the complete {@link ImportSystem} model. This deserializer only maps that model to the
 * JPA entities through the service mappers and the {@link SystemEntitySeam}; it does not touch the
 * archive itself.
 */
@Component
public class ServiceDeserializer {
    private final IntegrationSystemReader integrationSystemReader;
    private final IntegrationSystemDtoMapper integrationSystemDtoMapper;
    private final SpecificationGroupDtoMapper specificationGroupDtoMapper;
    private final SystemModelDtoMapper systemModelDtoMapper;

    @Autowired
    public ServiceDeserializer(
            IntegrationSystemReader integrationSystemReader,
            IntegrationSystemDtoMapper integrationSystemDtoMapper,
            SpecificationGroupDtoMapper specificationGroupDtoMapper,
            SystemModelDtoMapper systemModelDtoMapper
    ) {
        this.integrationSystemReader = integrationSystemReader;
        this.integrationSystemDtoMapper = integrationSystemDtoMapper;
        this.specificationGroupDtoMapper = specificationGroupDtoMapper;
        this.systemModelDtoMapper = systemModelDtoMapper;
    }

    public IntegrationSystem deserializeSystem(File serviceFile) {
        try {
            ImportSystem importSystem = integrationSystemReader.read(serviceFile);
            return toPersistenceEntity(importSystem);
        } catch (ServiceImportException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private IntegrationSystem toPersistenceEntity(ImportSystem importSystem) {
        IntegrationSystem integrationSystem = integrationSystemDtoMapper.toInternalEntity(importSystem);
        for (ImportSpecificationGroup importGroup : importSystem.getSpecificationGroups()) {
            SpecificationGroup specificationGroup = specificationGroupDtoMapper.toInternalEntity(importGroup);
            integrationSystem.addSpecificationGroup(specificationGroup);
            for (ImportSystemModel importModel : importGroup.getSystemModels()) {
                SystemModel systemModel = systemModelDtoMapper.toInternalEntity(importModel);
                specificationGroup.addSystemModel(systemModel);
                for (ImportSpecificationSource importSource : importModel.getSpecificationSources()) {
                    SpecificationSource specificationSource =
                            SystemEntitySeam.toPersistenceSpecificationSource(importSource, importSource.getSource());
                    systemModel.addProvidedSpecificationSource(specificationSource);
                }
            }
        }
        return integrationSystem;
    }
}
