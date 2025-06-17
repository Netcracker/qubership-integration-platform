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

package org.qubership.integration.platform.runtime.catalog.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SystemModelLibraryGenerationException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.codegen.SystemModelCodeGenerator;
import org.qubership.integration.platform.runtime.catalog.service.codegen.TargetProtocol;
import org.qubership.integration.platform.runtime.catalog.service.compiler.CompilationError;
import org.qubership.integration.platform.runtime.catalog.service.compiler.CompilerService;
import org.qubership.integration.platform.runtime.catalog.service.compiler.JarBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Manifest;
import javax.annotation.Nullable;

import static java.util.Objects.isNull;

@Slf4j
@Service
public class SystemModelBaseService {

    public static final String SYSTEM_MODEL_WITH_ID_NOT_FOUND_MESSAGE = "Can't find system model with id: ";
    protected static final String EMPTY_STRING = "";

    protected final SystemModelRepository systemModelRepository;
    protected final List<SystemModelCodeGenerator> codeGenerators;
    protected final CompilerService compilerService;
    protected final SystemModelLabelsRepository systemModelLabelsRepository;
    protected final ActionsLogService actionLogger;

    @Autowired
    public SystemModelBaseService(
            SystemModelRepository systemModelRepository,
            List<SystemModelCodeGenerator> codeGenerators,
            CompilerService compilerService,
            SystemModelLabelsRepository systemModelLabelsRepository,
            ActionsLogService actionLogger
    ) {
        this.systemModelRepository = systemModelRepository;
        this.codeGenerators = codeGenerators;
        this.compilerService = compilerService;
        this.systemModelLabelsRepository = systemModelLabelsRepository;
        this.actionLogger = actionLogger;
    }

    public SystemModel getSystemModel(String modelId) {
        return systemModelRepository.findById(modelId)
                .orElseThrow(() -> new EntityNotFoundException(SYSTEM_MODEL_WITH_ID_NOT_FOUND_MESSAGE + modelId));
    }

    public String getMainSystemModelSource(String modelId) {
        SystemModel systemModel = getSystemModel(modelId);
        if (systemModel.getSpecificationSources() != null) {
            return systemModel.getSpecificationSources()
                    .stream()
                    .filter(SpecificationSource::isMainSource)
                    .findFirst()
                    .map(SpecificationSource::getSource)
                    .orElse(EMPTY_STRING);
        }
        return EMPTY_STRING;
    }

    public @Nullable SpecificationSource getMainSystemModelSpecSource(String modelId) {
        SystemModel systemModel = getSystemModel(modelId);
        if (systemModel.getSpecificationSources() != null) {
            return systemModel.getSpecificationSources()
                    .stream()
                    .filter(SpecificationSource::isMainSource)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    public List<SystemModel> getSystemModelsBySpecificationGroupId(String specificationGroupId) {
        return systemModelRepository.findAllBySpecificationGroupId(specificationGroupId);
    }

    public long countBySpecificationGroupIdAndVersion(String specificationGroupId, String version) {
        return systemModelRepository.countBySpecificationGroupIdAndVersion(specificationGroupId, version);
    }

    @Transactional
    public SystemModel save(SystemModel model) {
        systemModelRepository.save(model);
        return model;
    }

    @Transactional
    public void delete(SystemModel model) {
        systemModelRepository.delete(model);
    }

    @Transactional
    public SystemModel update(SystemModel model) {
        model = systemModelRepository.save(model);
        return model;
    }

    @Transactional
    public void updateCompiledLibrariesForSystem(String systemId) {
        systemModelRepository
                .findSystemModelsBySpecificationGroupSystemId(systemId)
                .forEach(this::patchModelWithCompiledLibrary);
    }

    @Transactional
    public void patchModelWithCompiledLibrary(SystemModel model) {
        if (isNull(model)) {
            return;
        }
        byte[] data = generateJar(model);
        CompiledLibrary compiledLibrary = model.getCompiledLibrary();
        if (isNull(compiledLibrary)) {
            compiledLibrary = new CompiledLibrary();
            model.setCompiledLibrary(compiledLibrary);
        }
        compiledLibrary.setName(buildJarFileName(model));
        compiledLibrary.setData(data);
    }

    protected void logModelAction(SystemModel model, SpecificationGroup group, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SPECIFICATION)
                .entityId(model.getId())
                .entityName(model.getName())
                .parentType(group == null ? null : EntityType.SPECIFICATION_GROUP)
                .parentId(group == null ? null : group.getId())
                .parentName(group == null ? null : group.getName())
                .operation(operation)
                .build());
    }

    private String buildJarFileName(SystemModel model) {
        IntegrationSystem system = model.getSpecificationGroup().getSystem();
        return String.format("%s-%s-%s.jar", system.getProtocol().name().toLowerCase(),
                sanitizeString(system.getName()).toLowerCase(), sanitizeString(model.getName()).toLowerCase());
    }

    private static String sanitizeString(String s) {
        return StringUtils.strip(s.replaceAll("[^\\d\\w_\\-.]+", "_"), "_");
    }


    private byte[] generateJar(SystemModel model) {
        try {
            SystemModelCodeGenerator codeGenerator = getCodeGenerator(model);
            if (isNull(codeGenerator)) {
                return null;
            }
            log.debug("Generating library source code for system model with id {}", model.getId());
            Map<String, String> code = codeGenerator.generateCode(model);
            if (code.isEmpty()) {
                log.debug("System model has no DTO classes: {}", model.getId());
            }
            Manifest manifest = codeGenerator.generateManifest(model);
            log.debug("Compiling library for system model with id {}", model.getId());
            Map<String, byte[]> compiledCode = code.isEmpty() ? Collections.emptyMap() : compilerService.compile(code);
            JarBuilder jarBuilder = new JarBuilder();
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                jarBuilder.writeJar(outputStream, compiledCode, manifest);
                outputStream.close();
                return outputStream.toByteArray();
            } catch (IOException exception) {
                throw new SystemModelLibraryGenerationException("Failed to build jar.", exception);
            }
        } catch (CompilationError exception) {
            throw new SystemModelLibraryGenerationException("Failed to compile code.", exception);
        } catch (Exception exception) {
            throw new SystemModelLibraryGenerationException("Failed to generate source code.", exception);
        }
    }

    private SystemModelCodeGenerator getCodeGenerator(SystemModel model) {
        OperationProtocol protocol = Optional.ofNullable(model)
                .map(SystemModel::getSpecificationGroup)
                .map(SpecificationGroup::getSystem)
                .map(IntegrationSystem::getProtocol)
                .orElse(null);
        if (isNull(protocol)) {
            return null;
        }
        return codeGenerators.stream().filter(generator ->
                Optional.ofNullable(generator.getClass().getAnnotation(TargetProtocol.class))
                        .map(TargetProtocol::protocol).map(protocol::equals).orElse(false)
        ).findFirst().orElse(null);
    }
}
