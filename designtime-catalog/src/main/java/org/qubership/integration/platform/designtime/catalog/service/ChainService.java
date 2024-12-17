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

package org.qubership.integration.platform.designtime.catalog.service;

import org.qubership.integration.platform.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.catalog.model.dto.system.UsedSystem;
import org.qubership.integration.platform.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.ChainLabel;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.*;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.ChainBaseService;
import org.qubership.integration.platform.catalog.util.ChainUtils;
import org.qubership.integration.platform.catalog.util.ElementUtils;
import org.qubership.integration.platform.designtime.catalog.configuration.aspect.ChainModification;
import org.qubership.integration.platform.designtime.catalog.model.enums.filter.FilterFeature;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.chain.ChainSearchRequestDTO;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.folder.FolderContentFilter;
import org.qubership.integration.platform.designtime.catalog.service.filter.ChainFilterSpecificationBuilder;
import org.qubership.integration.platform.designtime.catalog.service.filter.complexFilters.ChainStatusFilters;
import org.qubership.integration.platform.designtime.catalog.service.filter.complexFilters.ElementFilter;
import org.qubership.integration.platform.designtime.catalog.service.filter.complexFilters.LoggingFilter;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.OVERRIDDEN_LABEL_NAME;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.OVERRIDES_LABEL_NAME;
import static java.util.Objects.nonNull;

@Slf4j
@Service
@Transactional
public class ChainService extends ChainBaseService {
    private static final String CHAIN_WITH_ID_NOT_FOUND_MESSAGE = "Can't find chain with id: ";
    private static final String CHAIN_TRIGGER = "chain-trigger-2";

    private final ChainRepository chainRepository;
    private final ElementRepository elementRepository;
    private final MaskedFieldRepository maskedFieldRepository;
    private final DependencyRepository dependencyRepository;
    private final ChainLabelsRepository chainLabelsRepository;
    private final FolderService folderService;
    private final ElementService elementService;
    private final DeploymentService deploymentService;
    private final ActionsLogService actionLogger;
    private final ElementUtils elementUtils;
    private final ChainRuntimePropertiesService chainRuntimePropertiesService;

    private final ChainFilterSpecificationBuilder chainFilterSpecificationBuilder;

    private final AuditingHandler auditingHandler;

    @Autowired
    public ChainService(ChainRepository chainRepository,
                        ElementRepository elementRepository,
                        MaskedFieldRepository maskedFieldRepository,
                        DependencyRepository dependencyRepository,
                        ChainLabelsRepository chainLabelsRepository,
                        ElementService elementService,
                        FolderService folderService,
                        @Lazy DeploymentService deploymentService,
                        ActionsLogService actionLogger,
                        ElementUtils elementUtils,
                        ChainFilterSpecificationBuilder chainFilterSpecificationBuilder,
                        AuditingHandler jpaAuditingHandler,
                        ChainRuntimePropertiesService chainRuntimePropertiesService) {
        super(chainRepository, elementService);
        this.chainLabelsRepository = chainLabelsRepository;
        this.folderService = folderService;
        this.elementService = elementService;
        this.chainRepository = chainRepository;
        this.elementRepository = elementRepository;
        this.maskedFieldRepository = maskedFieldRepository;
        this.dependencyRepository = dependencyRepository;
        this.deploymentService = deploymentService;
        this.actionLogger = actionLogger;
        this.elementUtils = elementUtils;
        this.chainFilterSpecificationBuilder = chainFilterSpecificationBuilder;
        this.auditingHandler = jpaAuditingHandler;
        this.chainRuntimePropertiesService = chainRuntimePropertiesService;
    }

    public List<Chain> findAll() {
        return chainRepository.findAll();
    }

    public Chain findById(String chainId) {
        return chainRepository.findById(chainId)
                .orElseThrow(() -> new EntityNotFoundException(CHAIN_WITH_ID_NOT_FOUND_MESSAGE + chainId));
    }

    public Map<String, String> provideNavigationPath(String chainId) {
        Chain chain = findById(chainId);
        return chain.getAncestors();
    }

    public List<Chain> findChainsInFolder(String folderId, FolderContentFilter filter) {
        Specification<Chain> specification = (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("parentFolder").get("id"), folderId);
        if (nonNull(filter)) {
            specification = specification.and(filter.getSpecification());
        }
        return chainRepository.findAll(specification);
    }

    public List<Chain> findBySystemAndOperationId(String systemId, String operationId) {
        List<ChainElement> elements = elementService.findBySystemIdAndOperationId(systemId, operationId);
        return getElementsChains(elements);
    }

    public List<Chain> findBySystemAndModelId(String systemId, String modelId) {
        List<ChainElement> elements = elementService.findBySystemAndModelId(systemId, modelId);
        return getElementsChains(elements);
    }

    public List<Chain> findBySystemAndGroupId(String systemId, String specificationGroupId) {
        List<ChainElement> elements = elementService.findBySystemIdAndSpecificationGroupId(
                systemId, specificationGroupId);
        return getElementsChains(elements);
    }

    public List<Chain> findBySystemId(String systemId) {
        List<ChainElement> elements = elementService.findBySystemId(systemId);
        return getElementsChains(elements);
    }

    public Map<String, List<Chain>> findBySystemIdGroupBySpecificationGroup(String systemId) {
        List<ChainElement> elements = elementService.findBySystemId(systemId);
        Map<String, List<ChainElement>> specGroupChainElement = new HashMap<>();
        for (ChainElement element : elements) {
            String specificationGroupKey = (String) element.getProperty(CamelOptions.SPECIFICATION_GROUP_ID);
            if (specificationGroupKey == null) {
                continue;
            }
            specGroupChainElement.computeIfAbsent(specificationGroupKey, s -> new ArrayList<>()).add(element);
        }
        Map<String, List<Chain>> specGroupChains = new HashMap<>();
        specGroupChainElement.forEach((key, chainElements) -> specGroupChains.put(key, getElementsChains(chainElements)));

        return specGroupChains;
    }

    private List<Chain> getElementsChains(List<ChainElement> elements) {
        return elements.stream()
                .map(ChainElement::getChain)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    public List<Chain> findInRoot(FolderContentFilter filter) {
        Specification<Chain> specification = (root, query, criteriaBuilder) ->
                criteriaBuilder.isNull(root.get("parentFolder"));
        if (nonNull(filter)) {
            specification = specification.and(filter.getSpecification());
        }
        return chainRepository.findAll(specification);
    }

    public Map<String, String> getNamesMapByChainIds(Set<String> chainIds) {
        return chainRepository.findAllById(chainIds).stream()
                .collect(Collectors.toMap(AbstractEntity::getId, AbstractEntity::getName));
    }

    public List<Chain> searchChains(ChainSearchRequestDTO systemSearchRequestDTO) {
        List<FilterRequestDTO> filters = Stream.of(
                FilterFeature.ID,
                FilterFeature.NAME,
                FilterFeature.DESCRIPTION,
                FilterFeature.PATH,
                FilterFeature.METHOD,
                FilterFeature.EXCHANGE,
                FilterFeature.TOPIC,
                FilterFeature.QUEUE,
                FilterFeature.LABELS
        ).map(feature -> FilterRequestDTO
                .builder()
                .feature(feature)
                .value(systemSearchRequestDTO.getSearchCondition())
                .condition(FilterCondition.CONTAINS)
                .build()
        ).toList();
        Specification<Chain> specification = chainFilterSpecificationBuilder.buildSearch(filters);
        return chainRepository.findAll(specification);
    }

    public List<Chain> findByFilterRequest(List<FilterRequestDTO> filters) {
        // TODO pagination
        Specification<Chain> specification = chainFilterSpecificationBuilder.buildFilter(filters);
        List<Chain> chains = chainRepository.findAll(specification);

        return applyComplexFilters(chains, filters);
    }

    public List<Chain> applyComplexFilters(List<Chain> chains, List<FilterRequestDTO> filters) {

        chains = new ChainStatusFilters(deploymentService).apply(chains,filters);
        chains = new ElementFilter().apply(chains,filters);
        chains = new LoggingFilter(chainRuntimePropertiesService).apply(chains,filters);

        return chains;
    }

    @ChainModification
    public Chain update(Chain chain, List<ChainLabel> newLabels, String parentFolderId) {
        auditingHandler.markModified(chain);
        Chain savedChain = upsertChain(chain, newLabels, parentFolderId);
        logChainAction(savedChain, LogOperation.UPDATE);
        return savedChain;
    }

    @ChainModification
    public Chain save(Chain chain, String parentFolderId) {
        auditingHandler.markModified(chain);
        Chain savedChain = upsertChain(chain, null, parentFolderId);
        logChainAction(savedChain, LogOperation.CREATE);
        return savedChain;
    }

    private Chain upsertChain(Chain chain, List<ChainLabel> newLabels, String parentFolderId) {
        chain = chainRepository.save(chain);
        if (parentFolderId != null) {
            Folder parentFolder = folderService.findEntityByIdOrNull(parentFolderId);
            parentFolder.addChildChain(chain);
        }
        if (newLabels != null) {
            replaceLabels(chain, newLabels);
        }
        return chain;
    }

    private void replaceLabels(Chain chain, List<ChainLabel> newLabels) {
        List<ChainLabel> finalNewLabels = newLabels;
        final Chain finalChain = chain;

        finalNewLabels.forEach(label -> label.setChain(finalChain));

        // Remove absent labels from db
        chain.getLabels().removeIf(l -> !l.isTechnical() && !finalNewLabels.stream().map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));
        // Add to database only missing labels
        finalNewLabels.removeIf(l -> l.isTechnical() || finalChain.getLabels().stream().filter(lab -> !lab.isTechnical()).map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));

        newLabels = chainLabelsRepository.saveAll(finalNewLabels);
        chain.addLabels(newLabels);
    }

    public Chain save(Chain chain) {
        return save(chain, null);
    }

    public void deleteById(String chainId) {
        deploymentService.deleteAllByChainId(chainId);
        Chain chain = findById(chainId);

        if (chain.getOverriddenByChain() != null) {
            Chain chainThatOverrides = chain.getOverriddenByChain();
            chainThatOverrides.getLabels().removeIf(label -> OVERRIDES_LABEL_NAME.equals(label.getName()));
            chainThatOverrides.setOverridesChainId(null);
            chainRepository.save(chainThatOverrides);
        }

        if (chain.getOverridesChain() != null) {
            Chain overriddenChain = chain.getOverridesChain();
            overriddenChain.getLabels().removeIf(label -> OVERRIDDEN_LABEL_NAME.equals(label.getName()));
            overriddenChain.setOverriddenByChainId(null);
            chainRepository.save(overriddenChain);
        }

        chainRepository.deleteById(chainId);

        logChainAction(chain, LogOperation.DELETE);
    }

    public List<UsedSystem> getUsedSystemIdsByChainIds(List<String> chainIds) {
        if (CollectionUtils.isEmpty(chainIds))
            return elementService.getAllUsedSystemIds();
        return elementService.getUsedSystemIdsByChainIds(chainIds);
    }

    public Chain move(String chainId, String targetFolderId) {
        Chain chain = findById(chainId);
        chain.setParentFolder(folderService.findEntityByIdOrNull(targetFolderId));
        logChainAction(chain, LogOperation.MOVE);
        return chain;
    }

    public Chain copy(String chainId, String targetFolderId) {
        return copy(findById(chainId), folderService.findEntityByIdOrNull(targetFolderId));
    }

    @ChainModification
    public Chain copy(Chain chain, Folder parentFolder) {
        Chain chainCopy = ChainUtils.getChainCopy(chain);

        chainCopy.setId(UUID.randomUUID().toString());
        chainCopy.setParentFolder(parentFolder);
        chainCopy.setName(generateCopyName(chainCopy, parentFolder == null ? null : parentFolder.getId()));
        chainCopy.setSnapshots(new ArrayList<>());
        chainCopy.setCurrentSnapshot(null);
        chainCopy.setDeployments(new ArrayList<>());

        chainCopy.setElements(copyElements(chainCopy.getElements(), chainCopy.getId()));
        Set<String> elementsModifiedState = saveElementsModifiedState(chainCopy.getElements());
        restoreElementsModifiedState(elementsModifiedState, chainCopy.getElements());

        chainCopy.setLabels(new HashSet<>());
        Set<ChainLabel> chainLabelsCopy = chain
                .getLabels()
                .stream()
                .map(label -> new ChainLabel(label.getName(),chainCopy))
                .collect(Collectors.toSet());
        chainCopy.setLabels(chainLabelsCopy);

        chainCopy.getDependencies().forEach(dependencyRepository::saveEntity);
        chainCopy.getMaskedFields().forEach(maskedFieldRepository::saveEntity);
        chainCopy.getElements().forEach(elementRepository::saveEntity);
        chainRepository.saveEntity(chainCopy);
        return chainCopy;
    }

    private void logChainAction(Chain chain, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.CHAIN)
                .entityId(chain.getId())
                .entityName(chain.getName())
                .parentType(chain.getParentFolder() == null ? null : EntityType.FOLDER)
                .parentId(chain.getParentFolder() == null ? null : chain.getParentFolder().getId())
                .parentName(chain.getParentFolder() == null ? null : chain.getParentFolder().getName())
                .operation(operation)
                .build());
    }

    public Chain duplicate(String chainId) {
        Chain chain = findById(chainId);
        Folder parentFolder = chain.getParentFolder();
        return copy(chain, parentFolder);
    }

    private String generateCopyName(Chain chainCopy, String targetFolderId) {
        String newName = chainCopy.getName();
        if (chainRepository.existsByNameAndParentFolderId(newName, targetFolderId)) {
            int copyNumber = 1;
            String tempName = newName + " (" + copyNumber + ")";
            while (chainRepository.existsByNameAndParentFolderId(tempName, targetFolderId)) {
                copyNumber++;
                tempName = newName + " (" + copyNumber + ")";
            }
            newName = newName + " (" + copyNumber + ")";
        }
        return newName;
    }

    private void restoreElementsModifiedState(Set<String> elementsModifiedState, List<ChainElement> elements) {
        for (ChainElement element : elements) {
            if (elementsModifiedState.contains(element.getId())) {
                element.setCreatedWhen(null);
                element.setModifiedWhen(null);
            } else {
                element.setModifiedWhen(Timestamp.valueOf(LocalDateTime.now()));
            }
        }
    }

    private Set<String> saveElementsModifiedState(List<ChainElement> elements) {
        Set<String> elementsModifiedState = new HashSet<>();
        for (ChainElement element : elements) {
            if (element.getCreatedWhen() == null)
                elementsModifiedState.add(element.getId());
        }
        return elementsModifiedState;
    }

    private void setDependencies(List<ChainElement> originalElements,
                                 Map<String, ChainElement> elementsMap) {

        for (ChainElement originalElement : originalElements) {
            ChainElement copiedElement = elementsMap.get(originalElement.getId());

            List<String> inputIdList = originalElement.getInputDependencies().stream()
                    .map(dep -> dep.getElementFrom().getId())
                    .filter(elId -> elementsMap.get(elId).getOutputDependencies().isEmpty())
                    .toList();
            List<String> outputIdList = originalElement.getOutputDependencies().stream()
                    .map(dep -> dep.getElementTo().getId())
                    .filter(elId -> elementsMap.get(elId).getInputDependencies().isEmpty())
                    .toList();

            List<Dependency> inputDependencies = inputIdList.stream()
                    .map(elementId -> {
                        ChainElement element = elementsMap.get(elementId);
                        return Dependency.of(element, copiedElement);
                    })
                    .collect(Collectors.toList());

            List<Dependency> outputDependencies = outputIdList.stream()
                    .map(elementId -> {
                        ChainElement element = elementsMap.get(elementId);
                        return Dependency.of(copiedElement, element);
                    })
                    .collect(Collectors.toList());

            copiedElement.setInputDependencies(inputDependencies);
            copiedElement.setOutputDependencies(outputDependencies);
        }
    }

    public List<ChainElement> copyElements(List<ChainElement> originalElements, String chainId) {
        Map<String, ChainElement> copiedElementsMap = new HashMap<>();
        Map<String, ChainElement> originalElementsMap = new HashMap<>();
        List<ChainElement> copiedElements = new ArrayList<>();

        for (ChainElement element : originalElements) {
            element.setId(UUID.randomUUID().toString());
            elementUtils.updateResetOnCopyProperties(element, chainId);
            copiedElementsMap.put(element.getId(), element);
            originalElementsMap.put(element.getId(), element);
            copiedElements.add(element);
        }

        for (Map.Entry<String, ChainElement> copiedElement : copiedElementsMap.entrySet()) {
            ContainerChainElement parent = originalElementsMap.get(copiedElement.getKey()).getParent();
            if (parent != null) {
                copiedElement.getValue().setParent((ContainerChainElement) copiedElementsMap.get(parent.getId()));
            }
        }

        setDependencies(originalElements, copiedElementsMap);

        return copiedElements;
    }

    private ChainElement copyElement(ChainElement originalElement) {

        ChainElement copiedElement = createChainElement(originalElement);

        if (originalElement.getCreatedWhen().getTime() == originalElement.getModifiedWhen().getTime()) {
            copiedElement.setCreatedWhen(null);
            copiedElement.setModifiedWhen(null);
        } else {
            copiedElement.setModifiedWhen(Timestamp.valueOf(LocalDateTime.now()));
        }

        return copiedElement;
    }

    public ChainElement createChainElement(ChainElement base) {
        ChainElement element = base.copy();
        if (CHAIN_TRIGGER.equals(element.getType())) {
            element.getProperties().put("elementId", element.getId());
        }
        element = elementService.save(element);

        if (base.getModifiedWhen().getTime() == base.getCreatedWhen().getTime()) {
            element.setCreatedWhen(null);
            element.setModifiedWhen(null);
        } else {
            element.setModifiedWhen(Timestamp.valueOf(LocalDateTime.now()));
        }

        element.setOriginalId(null);

        return element;
    }

    public boolean containsDeprecatedElements(Chain chain) {
        return chain.getElements().stream()
                .anyMatch(elementService::isElementDeprecated);
    }

    public boolean containsUnsupportedElements(Chain chain) {
        return chain.getElements().stream()
                .anyMatch(elementService::isElementUnsupported);
    }

    public List<Chain> findAllChainsToRootParentFolder(String openedFolderId) {
        return chainRepository.findAllChainsToRootParentFolder(openedFolderId);
    }

    public long getChainsCount() {
        return chainRepository.count();
    }
}
