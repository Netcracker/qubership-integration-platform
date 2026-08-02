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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationDeleteException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroupLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.ApiGroupLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.ApiGroupRepository;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ProtocolExtractionService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.qubership.integration.platform.runtime.catalog.util.MultipartFileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.DIFFERENT_PROTOCOL_ERROR_MESSAGE;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.INVALID_INPUT_FILE_ERROR_MESSAGE;

@Slf4j
@Service
public class ApiGroupService extends AbstractApiGroupService {

    private final SystemService systemService;
    private final ProtocolExtractionService protocolExtractionService;
    private final ElementHelperService elementHelperService;

    @Autowired
    public ApiGroupService(
            ApiGroupRepository apiGroupRepository,
            ActionsLogService actionLogger,
            SystemService systemService,
            ProtocolExtractionService protocolExtractionService,
            ApiGroupLabelsRepository apiGroupLabelsRepository,
            ElementHelperService elementHelperService
    ) {
        super(apiGroupRepository, actionLogger, apiGroupLabelsRepository);
        this.systemService = systemService;
        this.protocolExtractionService = protocolExtractionService;
        this.elementHelperService = elementHelperService;
    }

    public void checkSpecificationGroupUniqueness(IntegrationSystem system) {
        if (CollectionUtils.isEmpty(system.getApiGroups())) {
            return;
        }
        List<String> ids = system.getApiGroups().stream().map(ApiGroup::getId).toList();
        ApiGroup duplicate = apiGroupRepository.findByIdInAndSystemIdNot(ids, system.getId());

        if (duplicate != null) {
            throw new DuplicateKeyException(
                    String.format("Specification group with id=%s already exists on another service %s",
                            duplicate.getId(), duplicate.getSystem().getId()));
        }
    }

    public ApiGroup createAndSaveSpecificationGroupWithProtocol(IntegrationSystem system,
                                                                String specificationName,
                                                                String protocol,
                                                                MultipartFile[] files,
                                                                String specificationUrl) {
        if (system == null) {
            throw new SpecificationImportException(SYSTEM_NOT_FOUND_ERROR_MESSAGE);
        }
        if (apiGroupRepository.findByNameAndSystem(specificationName, system) != null) {
            throw new SpecificationImportException(SPECIFICATION_GROUP_NAME_ERROR_MESSAGE);
        } else {
            setSystemProtocol(system, protocol, files);

            ApiGroup specificationGroup = new ApiGroup();
            specificationGroup.setId(buildSpecificationGroupId(system, specificationName));
            specificationGroup.setName(specificationName);
            specificationGroup.setUrl(specificationUrl);

            specificationGroup = apiGroupRepository.save(specificationGroup);

            system.addApiGroup(specificationGroup);

            systemService.update(system, false);

            logSpecGroupAction(specificationGroup, system, LogOperation.CREATE);
            return specificationGroup;
        }
    }

    public Optional<ApiGroup> deleteByIdExists(String specificationGroupId) {
        Optional<ApiGroup> specificationGroupOptional = apiGroupRepository.findById(specificationGroupId);
        if (specificationGroupOptional.isPresent()) {
            if (elementHelperService.isSystemModelUsedByElement(specificationGroupId)) {
                throw new IllegalArgumentException("Specification group used by one or more chains");
            }

            ApiGroup specificationGroup = specificationGroupOptional.get();
            apiGroupRepository.delete(specificationGroup);
            logSpecGroupAction(specificationGroup, specificationGroup.getSystem(), LogOperation.DELETE);
            return Optional.of(specificationGroup);
        }

        return Optional.empty();
    }

    private void setSystemProtocol(IntegrationSystem system, String protocol, MultipartFile[] files) {
        OperationProtocol operationProtocol;

        try {
            if (system.getProtocol() == null) {
                if (StringUtils.isBlank(protocol)) {
                    operationProtocol = protocolExtractionService.getOperationProtocol(MultipartFileUtils.extractArchives(files));
                } else {
                    operationProtocol = OperationProtocol.fromValue(protocol);
                }

                if (isNull(operationProtocol)) {
                    throw new SpecificationImportException("Unsupported protocol: " + protocol);
                } else {
                    systemService.validateSpecificationProtocol(system, operationProtocol);
                    system.setProtocol(operationProtocol);
                }
            } else {
                operationProtocol = protocolExtractionService.getOperationProtocol(MultipartFileUtils.extractArchives(files));

                if (operationProtocol != null && !system.getProtocol().equals(operationProtocol)) {
                    throw new SpecificationImportException(DIFFERENT_PROTOCOL_ERROR_MESSAGE);
                }
            }
        } catch (IOException exception) {
            throw new SpecificationImportException(INVALID_INPUT_FILE_ERROR_MESSAGE, exception);
        }
    }

    public ApiGroup createAndSaveSpecificationGroup(String systemId,
                                                    String specificationName,
                                                    String protocol,
                                                    MultipartFile[] files) {
        return createAndSaveSpecificationGroupWithProtocol(systemService.getByIdOrNull(systemId), specificationName, protocol, files, null);
    }

    public ApiGroup createAndSaveSpecificationGroup(IntegrationSystem system,
                                                    String specificationId,
                                                    String specificationName,
                                                    String specificationType,
                                                    String specificationUrl,
                                                    Boolean synchronization) {
        if (system == null) {
            throw new SpecificationImportException(SYSTEM_NOT_FOUND_ERROR_MESSAGE);
        }
        if (apiGroupRepository.findByNameAndSystem(specificationName, system) != null) {
            throw new SpecificationImportException(SPECIFICATION_GROUP_NAME_ERROR_MESSAGE);
        } else {
            ApiGroup specificationGroup = new ApiGroup();
            specificationGroup.setName(specificationName);
            specificationGroup.setId(specificationId);
            specificationGroup.setUrl(specificationUrl);
            specificationGroup.setSynchronization(synchronization);

            specificationGroup = apiGroupRepository.save(specificationGroup);

            system.addApiGroup(specificationGroup);

            system.setProtocol(protocolExtractionService.getProtocol(specificationType));
            systemService.update(system);

            logSpecGroupAction(specificationGroup, system, LogOperation.CREATE);
            return specificationGroup;
        }
    }

    public ApiGroup createAndSaveSpecificationGroup(
            String systemId,
            String name,
            String description,
            String url,
            boolean synchronization) {
        return createAndSaveSpecificationGroup(systemService.getByIdOrNull(systemId), name, description, url, synchronization);
    }

    public ApiGroup createAndSaveSpecificationGroup(
            IntegrationSystem system,
            String groupName,
            String description,
            String url,
            boolean synchronization
    ) {
        if (system == null) {
            throw new SpecificationImportException(SYSTEM_NOT_FOUND_ERROR_MESSAGE);
        }
        if (apiGroupRepository.findByNameAndSystem(groupName, system) != null) {
            throw new SpecificationImportException(SPECIFICATION_GROUP_NAME_ERROR_MESSAGE);
        } else {
            ApiGroup specificationGroup = new ApiGroup();
            specificationGroup.setId(buildSpecificationGroupId(system, groupName));
            specificationGroup.setName(groupName);
            specificationGroup.setDescription(description);
            specificationGroup.setUrl(url);
            specificationGroup.setSynchronization(synchronization);

            specificationGroup = apiGroupRepository.save(specificationGroup);

            system.addApiGroup(specificationGroup);

            systemService.update(system);

            logSpecGroupAction(specificationGroup, system, LogOperation.CREATE);
            return specificationGroup;
        }
    }

    public ApiGroup createAndSaveUniqueSpecificationGroup(IntegrationSystem system,
                                                          String specificationName,
                                                          String specificationType,
                                                          String specificationUrl,
                                                          Boolean synchronization) {
        String name = getUniqueName(system, specificationName);
        String id = buildSpecificationGroupId(system, specificationName);
        if (apiGroupWithIdExists(system.getApiGroups(), id)) {
            id = buildSpecificationGroupId(system, name);
        }
        return createAndSaveSpecificationGroup(system, id, name, specificationType, specificationUrl, synchronization);
    }

    public ApiGroup getSpecificationGroupBySystemIdAndUrl(String systemId, String url) {
        try {
            return apiGroupRepository.findBySystemIdAndUrl(systemId, url);
        } catch (IncorrectResultSizeDataAccessException exception) {
            throw new DuplicateKeyException("Not unique specification group url found: " + url, exception);
        }
    }

    public ApiGroup getSpecificationGroupByNameAndSystem(String specificationGroupName, IntegrationSystem system) {
        try {
            return apiGroupRepository.findByNameAndSystem(specificationGroupName, system);
        } catch (IncorrectResultSizeDataAccessException exception) {
            log.error("Not unique specification group name {}, for system {}", specificationGroupName, system.getName());
            throw new DuplicateKeyException("Not unique specification group name found: " + specificationGroupName, exception);
        }
    }

    public List<ApiGroup> getSpecificationGroups(String systemId) {
        List<ApiGroup> specificationGroups = apiGroupRepository.findAllBySystemId(systemId);
        specificationGroups.forEach(this::enrichSpecificationGroupWithChains);
        // Immutable is fine: the sort below reorders each group's own model list, not this one.
        List<ApiGroup> specificationGroupsSorted = specificationGroups.stream()
                .sorted((sg1, sg2) -> sg2.getName().compareTo(sg1.getName()))
                .toList();

        specificationGroupsSorted.forEach(specificationGroup -> specificationGroup.getSystemModels().sort(Comparator.comparing(SystemModel::getVersion)));
        return specificationGroupsSorted;
    }

    public void delete(String specificationGroupId) {
        if (elementHelperService.isSystemModelUsedByElement(specificationGroupId)) {
            throw new SpecificationDeleteException("Specification group used by one or more chains");
        }

        ApiGroup specificationGroup = apiGroupRepository.getReferenceById(specificationGroupId);
        IntegrationSystem system = specificationGroup.getSystem();

        apiGroupRepository.delete(specificationGroup);
        system.removeApiGroup(specificationGroup);

        logSpecGroupAction(specificationGroup, system, LogOperation.DELETE);
    }

    public ApiGroup update(ApiGroup specificationGroup) {
        return update(specificationGroup, null);
    }

    public ApiGroup update(ApiGroup specificationGroup, List<ApiGroupLabel> newLabels) {
        replaceLabels(specificationGroup, newLabels);
        specificationGroup = apiGroupRepository.save(specificationGroup);
        logSpecGroupAction(specificationGroup, specificationGroup.getSystem(), LogOperation.UPDATE);
        return specificationGroup;
    }

    public void replaceLabels(ApiGroup specificationGroup, List<ApiGroupLabel> newLabels) {
        if (newLabels == null) {
            return;
        }
        List<ApiGroupLabel> finalNewLabels = newLabels;
        final ApiGroup finalSpecificationGroup = specificationGroup;

        finalNewLabels.forEach(label -> label.setApiGroup(finalSpecificationGroup));

        // Remove absent labels from db
        specificationGroup.getLabels().removeIf(l -> !l.isTechnical() && !finalNewLabels.stream().map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));
        // Add to database only missing labels
        finalNewLabels.removeIf(l -> l.isTechnical() || finalSpecificationGroup.getLabels().stream().filter(lab -> !lab.isTechnical()).map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));

        newLabels = apiGroupLabelsRepository.saveAll(finalNewLabels);
        specificationGroup.addLabels(newLabels);
    }

    public String getUniqueName(IntegrationSystem system, String desiredName) {
        String newName = desiredName;
        int iterator = 0;
        while (apiGroupWithNameExists(system.getApiGroups(), newName)) {
            iterator++;
            newName = desiredName + " (" + iterator + ")";
        }
        return newName;
    }

    private boolean apiGroupWithNameExists(Collection<ApiGroup> apiGroups, String name) {
        if (apiGroups == null) {
            return false;
        }
        return apiGroups.stream().anyMatch(apiGroup ->
                name.equals(apiGroup.getName()));
    }

    private boolean apiGroupWithIdExists(Collection<ApiGroup> apiGroups, String id) {
        return nonNull(apiGroups)
               && apiGroups.stream().map(ApiGroup::getId).anyMatch(id::equals);
    }

    private void enrichSpecificationGroupWithChains(ApiGroup specificationGroup) {
        List<Chain> chains = elementHelperService.findBySystemAndGroupId(specificationGroup.getSystem().getId(), specificationGroup.getId());
        specificationGroup.setChains(chains);

        for (SystemModel model : specificationGroup.getSystemModels()) {
            List<Chain> modelChains = chains.stream()
                    .flatMap(modelChain -> modelChain.getElements().stream())
                    .filter(chainElement -> StringUtils.equals(model.getId(), chainElement.getPropertyAsString(CamelOptions.MODEL_ID)))
                    .map(ChainElement::getChain)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            model.setChains(modelChains);
        }
    }
}
