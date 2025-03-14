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

package org.qubership.integration.platform.catalog.service.exportimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.qubership.integration.platform.catalog.context.RequestIdContext;
import org.qubership.integration.platform.catalog.exception.*;
import org.qubership.integration.platform.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.catalog.persistence.configs.entity.ConfigParameter;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.qubership.integration.platform.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.catalog.service.ConfigParameterService;
import org.qubership.integration.platform.catalog.service.SystemBaseService;
import org.qubership.integration.platform.catalog.service.SystemModelBaseService;
import org.qubership.integration.platform.catalog.service.parsers.OperationParserService;
import org.qubership.integration.platform.catalog.util.MultipartFileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Service
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class SpecificationImportService {
    private static final String IMPORT_SESSION_NOT_FOUND_MESSAGE = "Import session with specified id not found";
    private static final String SET_IMPORT_SESSION_STATUS_MESSAGE = "Unable to set import session status";
    private static final Pattern wsdlExtensionPattern = Pattern.compile("^.*\\.(WSDL)$", Pattern.CASE_INSENSITIVE);

    private final OperationParserService operationParserService;
    private final SpecificationSourceRepository specificationSourceRepository;
    private final SpecificationGroupRepository specificationGroupRepository;
    private final ProtocolExtractionService protocolExtractionService;
    private final ConfigParameterService configParameterService;
    private final ObjectMapper objectMapper;
    private final SystemBaseService systemBaseService;
    private final SystemModelBaseService systemModelService;

    @Autowired
    public SpecificationImportService(OperationParserService operationParserService,
                                      SpecificationGroupRepository specificationGroupRepository,
                                      SpecificationSourceRepository specificationSourceRepository,
                                      ConfigParameterService configParameterService,
                                      ProtocolExtractionService protocolExtractionService,
                                      @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
                                      SystemBaseService systemBaseService,
                                      SystemModelBaseService systemModelService
    ) {
        this.operationParserService = operationParserService;
        this.specificationGroupRepository = specificationGroupRepository;
        this.specificationSourceRepository = specificationSourceRepository;
        this.protocolExtractionService = protocolExtractionService;
        this.configParameterService = configParameterService;
        this.objectMapper = objectMapper;
        this.systemBaseService = systemBaseService;
        this.systemModelService = systemModelService;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    private static class ImportSessionStatusDTO {
        private boolean importIsDone;
        private String errorMessage;
        private String warningMessage;
        private String stackTrace;
        private boolean business;
    }

    public static final String SPECIFICATION_IMPORT_STATUS_CONFIG_NAMESPACE = "specification-import-status";

    public Boolean importSessionIsDone(String importId) {
        ImportSessionStatusDTO sessionStatus = getImportSessionStatus(importId);

        if (sessionStatus.isBusiness()) {
            throw new SpecificationImportException(sessionStatus.getErrorMessage());
        }
        if (!StringUtils.isBlank(sessionStatus.getErrorMessage())) {
            deleteImportSessionStatus(importId);
            throw new SpecificationImportException(sessionStatus.getErrorMessage(), sessionStatus.getStackTrace());
        }
        if (!StringUtils.isBlank(sessionStatus.getWarningMessage())) {
            deleteImportSessionStatus(importId);
            throw new SpecificationImportWarningException(sessionStatus.getWarningMessage(), sessionStatus.getStackTrace());
        }
        if (sessionStatus.isImportIsDone())
            deleteImportSessionStatus(importId);

        return sessionStatus.isImportIsDone();
    }

    private void deleteImportSessionStatus(String importId) {
        configParameterService.deleteByName(SPECIFICATION_IMPORT_STATUS_CONFIG_NAMESPACE, importId);
    }

    public String importSpecification(String specificationGroupId, MultipartFile[] files) {
        deleteObsoleteImportSessionStatuses();
        IntegrationSystem system = specificationGroupRepository.getReferenceById(specificationGroupId).getSystem();

        Collection<MultipartFile> extractedFiles;
        try {
            extractedFiles = MultipartFileUtils.extractArchives(files);
        } catch (IOException exception) {
            throw new SpecificationImportException(ExportImportConstants.INVALID_INPUT_FILE_ERROR_MESSAGE, exception);
        }

        OperationProtocol importingFilesProtocol = protocolExtractionService.getOperationProtocol(extractedFiles);

        systemBaseService.validateSpecificationProtocol(system, importingFilesProtocol);

        OperationProtocol protocol = system.getProtocol();
        if (isNull(protocol)) {
            protocol = importingFilesProtocol;
            system.setProtocol(importingFilesProtocol);
        } else if (!system.getProtocol().equals(importingFilesProtocol)) {
            throw new SpecificationImportException(ExportImportConstants.DIFFERENT_PROTOCOL_ERROR_MESSAGE);
        }

        List<SpecificationSource> specificationSources = getSpecificationSources(protocol, extractedFiles);
        String importId = UUID.randomUUID().toString();
        startImportSessionStatus(importId);
        String requestId = RequestIdContext.get();

        StringBuilder message = new StringBuilder();
        try {
            CompletableFuture<SystemModel> future = operationParserService.parse(
                    protocol.type.toLowerCase(),
                    specificationGroupId,
                    specificationSources,
                    false,
                    Collections.emptySet(),
                    message::append
            ).thenApply(model -> compileModelLibraryOrDeleteModel(requestId, model));

            future.whenComplete((t, e) -> {
                onImportSpecificationTaskComplete(importId, e, message.toString());
                if (e != null) {
                    specificationSourceRepository.deleteAll(specificationSources);
                }
            });
        } catch (Exception e) {
            specificationSourceRepository.saveAll(specificationSources);
            throw new RuntimeException(e.getMessage());
        }

        return importId;
    }

    public CompletableFuture<SystemModel> importSimpleSpecification(String fileName,
                                                                    String specificationGroupId,
                                                                    String specificationType,
                                                                    String content,
                                                                    Set<String> oldSystemModelsIds,
                                                                    Consumer<String> messageHandler) {
        SpecificationSource source = SpecificationSource.builder()
                .name(fileName)
                .isMainSource(true)
                .source(content)
                .build();
        specificationSourceRepository.save(source);
        Collection<SpecificationSource> sources = Collections.singletonList(source);

        String requestId = RequestIdContext.get();
        return operationParserService.parse(
                    specificationType.toLowerCase(),
                    specificationGroupId,
                    sources,
                    true,
                    oldSystemModelsIds,
                    messageHandler)
                .thenApply(model -> compileModelLibraryOrDeleteModel(requestId, model));
    }

    private SystemModel compileModelLibraryOrDeleteModel(String requestId, SystemModel model) {
        try {
            RequestIdContext.set(requestId);
            systemModelService.patchModelWithCompiledLibrary(model);
            return systemModelService.save(model);
        } catch (Exception exception) {
            systemModelService.delete(model);
            throw exception;
        }
    }

    private boolean isMainSpecificationSource(OperationProtocol protocol, MultipartFile file) {
        return (OperationProtocol.SOAP.equals(protocol) && wsdlExtensionPattern.matcher(file.getOriginalFilename()).matches())
                || (List.of(
                OperationProtocol.HTTP,
                OperationProtocol.AMQP,
                OperationProtocol.GRAPHQL,
                OperationProtocol.KAFKA
        ).contains(protocol));
    }

    private List<SpecificationSource> getSpecificationSources(
            OperationProtocol protocol,
            Collection<MultipartFile> files
    ) {
        List<SpecificationSource> specificationSources = files.stream().map(file -> {
            try {
                String content = new String(file.getBytes());
                return SpecificationSource.builder()
                        .name(file.getOriginalFilename())
                        .isMainSource(isMainSpecificationSource(protocol, file))
                        .source(content)
                        .build();
            } catch (IOException exception) {
                throw new SpecificationImportException(ExportImportConstants.INVALID_INPUT_FILE_ERROR_MESSAGE, exception);
            }
        }).collect(Collectors.toList());
        boolean mainSourceIsNotSpecified = specificationSources.stream().noneMatch(SpecificationSource::isMainSource);
        if (!specificationSources.isEmpty() && mainSourceIsNotSpecified) {
            specificationSources.get(0).setMainSource(true);
        }
        specificationSourceRepository.saveAll(specificationSources);
        return specificationSources;
    }

    private void onImportSpecificationTaskComplete(String importId, Throwable exception, String additionalMessage) {
        String errorMessage = null;
        String stackTrace = null;
        boolean business = false;
        if (nonNull(exception)) {
            if (nonNull(exception.getCause())) {
                exception = exception.getCause();
            }
            errorMessage = exception.getMessage();
            if (exception instanceof SystemModelLibraryGenerationException libraryGenerationException) {
                errorMessage += " " + libraryGenerationException.getOriginalException().getMessage();
            }
            if (StringUtils.isNotBlank(additionalMessage)) {
                errorMessage += " " + additionalMessage;
            }
            business = exception instanceof SpecificationSimilarVersionException;
            if (exception instanceof CatalogRuntimeException catalogRuntimeException) {
                stackTrace = Optional.ofNullable(catalogRuntimeException.getOriginalException())
                        .map(ExceptionUtils::getStackTrace)
                        .orElse(null);
            }
        }
        saveImportSessionStatus(importId, true, errorMessage, additionalMessage, stackTrace, business);
    }

    private void saveImportSessionStatus(
            String importId,
            boolean importIsDone,
            String errorMessage,
            String warningMessage,
            String stackTrace,
            boolean business
    ) {
        ImportSessionStatusDTO dto = new ImportSessionStatusDTO(importIsDone, errorMessage, warningMessage, stackTrace, business);
        ConfigParameter cp = new ConfigParameter(SPECIFICATION_IMPORT_STATUS_CONFIG_NAMESPACE, importId);
        try {
            cp.setString(objectMapper.writeValueAsString(dto));
            configParameterService.update(cp);
        } catch (JsonProcessingException e) {
            throw new SpecificationImportException(SET_IMPORT_SESSION_STATUS_MESSAGE, e);
        }
    }

    private void deleteObsoleteImportSessionStatuses() {
        final int entityExpiredTimeoutMinutes = 15;

        List<ConfigParameter> params = configParameterService.findAllByNamespace(SPECIFICATION_IMPORT_STATUS_CONFIG_NAMESPACE);
        for (ConfigParameter cp : params) {
            if (cp.getModifiedWhen().before(
                    Timestamp.valueOf(LocalDateTime.now().minusMinutes(entityExpiredTimeoutMinutes))))
                configParameterService.delete(cp);
        }
    }

    private void startImportSessionStatus(String importId) {
        saveImportSessionStatus(importId, false, null, null, null, false);
    }

    private ImportSessionStatusDTO getImportSessionStatus(String importId) {
        ConfigParameter cp = configParameterService.findByName(SPECIFICATION_IMPORT_STATUS_CONFIG_NAMESPACE, importId);
        String rawStatusData = cp == null ? null : cp.getString();

        ImportSessionStatusDTO status = null;
        try {
            status = objectMapper.readValue(rawStatusData, ImportSessionStatusDTO.class);
        } catch (JsonProcessingException | RuntimeException ignored) {
        }
        if (status == null)
            throw new SpecificationImportException(IMPORT_SESSION_NOT_FOUND_MESSAGE);
        return status;
    }
}

