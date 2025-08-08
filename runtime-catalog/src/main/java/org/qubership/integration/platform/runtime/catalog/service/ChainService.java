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
import org.codehaus.plexus.util.StringUtils;
import org.qubership.integration.platform.runtime.catalog.configuration.aspect.ChainModification;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.UsedSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.ChainLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.*;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainSearchRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.ListFolderRequest;
import org.qubership.integration.platform.runtime.catalog.service.filter.ChainFilterSpecificationBuilder;
import org.qubership.integration.platform.runtime.catalog.service.filter.complex.ChainStatusFilters;
import org.qubership.integration.platform.runtime.catalog.service.filter.complex.ElementFilter;
import org.qubership.integration.platform.runtime.catalog.service.filter.complex.LoggingFilter;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.util.ChainUtils;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
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

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.OVERRIDDEN_LABEL_NAME;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.OVERRIDES_LABEL_NAME;

@Slf4j
@Service
@Transactional
public class ChainService extends ChainBaseService {
    private static final String CHAIN_TRIGGER = "chain-trigger-2";

    private final ChainRepository chainRepository;
    private final ElementRepository elementRepository;
    private final MaskedFieldRepository maskedFieldRepository;
    private final DependencyRepository dependencyRepository;
    private final ChainLabelsRepository chainLabelsRepository;
    private final FolderService folderService;
    private final ElementService elementService;
    private final DeploymentService deploymentService;
    private final RuntimeDeploymentService runtimeDeploymentService;
    private final ChainRuntimePropertiesService chainRuntimePropertiesService;
    private final ActionsLogService actionLogger;
    private final ElementUtils elementUtils;
    private final ChainFilterSpecificationBuilder chainFilterSpecificationBuilder;
    private final AuditingHandler auditingHandler;
    private final ChainFinderService chainFinderService;

    @Autowired
    public ChainService(
            ChainRepository chainRepository,
            ElementService elementService,
            ElementRepository elementRepository,
            MaskedFieldRepository maskedFieldRepository,
            DependencyRepository dependencyRepository,
            ChainLabelsRepository chainLabelsRepository,
            FolderService folderService,
            @Lazy DeploymentService deploymentService,
            RuntimeDeploymentService runtimeDeploymentService,
            ActionsLogService actionLogger,
            ElementUtils elementUtils,
            ChainRuntimePropertiesService chainRuntimePropertiesService,
            ChainFilterSpecificationBuilder chainFilterSpecificationBuilder,
            AuditingHandler auditingHandler,
            ChainFinderService chainFinderService,
            ContextBaseService contextBaseService
    ) {
        super(chainRepository, elementService, contextBaseService);
        this.chainRepository = chainRepository;
        this.elementRepository = elementRepository;
        this.maskedFieldRepository = maskedFieldRepository;
        this.dependencyRepository = dependencyRepository;
        this.chainLabelsRepository = chainLabelsRepository;
        this.folderService = folderService;
        this.elementService = elementService;
        this.deploymentService = deploymentService;
        this.runtimeDeploymentService = runtimeDeploymentService;
        this.actionLogger = actionLogger;
        this.elementUtils = elementUtils;
        this.chainRuntimePropertiesService = chainRuntimePropertiesService;
        this.chainFilterSpecificationBuilder = chainFilterSpecificationBuilder;
        this.auditingHandler = auditingHandler;
        this.chainFinderService = chainFinderService;
    }

    public Boolean exists(String chainId) {
        return chainRepository.existsById(chainId);
    }

    public List<String> getSubChainsIds(List<String> chainsIds, List<String> resultChainsIds) {
        resultChainsIds.addAll(chainsIds);
        List<String> subChainsIds = chainRepository.findSubChains(chainsIds);
        subChainsIds.removeAll(resultChainsIds);
        if (!subChainsIds.isEmpty()) {
            getSubChainsIds(subChainsIds, resultChainsIds);
        }

        return resultChainsIds;
    }

    public boolean setOverriddenById(String chainId, String overriddenById) {
        Optional<Chain> optionalChain = chainFinderService.tryFindById(chainId);
        if (optionalChain.isPresent()) {
            Chain chain = optionalChain.get();
            chain.setOverriddenByChainId(overriddenById);

            if (chain.getLabels().stream().noneMatch(label -> OVERRIDDEN_LABEL_NAME.equals(label.getName()))) {
                chain.addLabel(ChainLabel.builder()
                        .name(OVERRIDDEN_LABEL_NAME)
                        .technical(true)
                        .chain(chain)
                        .build());
            }
            chainRepository.save(chain);
            return true;
        }
        return false;
    }

    public boolean setOverridesChainId(String chainId, String overriddenById) {
        Optional<Chain> optionalChain = chainFinderService.tryFindById(overriddenById);
        if (optionalChain.isPresent()) {
            Chain chain = optionalChain.get();
            chain.setOverridesChainId(chainId);

            if (chain.getLabels().stream().noneMatch(label -> OVERRIDES_LABEL_NAME.equals(label.getName()))) {
                chain.addLabel(ChainLabel.builder()
                        .name(OVERRIDES_LABEL_NAME)
                        .technical(true)
                        .chain(chain)
                        .build());
            }
            chainRepository.save(chain);
            return true;
        }
        return false;
    }

    public void overrideModificationTimestamp(Chain chain, Timestamp timestamp) {
        chainRepository.updateModificationTimestamp(chain.getId(), timestamp);
    }

    public void setActualizedChainState(Chain currentChainState, Chain newChainState) {
        chainRepository.actualizeObjectState(currentChainState, newChainState);
    }

    public String getChainHash(String chainId) {
        return chainRepository.getChainLastImportHash(chainId);
    }

    public void clearContext() {
        chainRepository.clearContext();
    }

    public Optional<Chain> deleteByIdIfExists(String chainId) {
        deploymentService.deleteAllByChainId(chainId);
        Optional<Chain> optionalChain = chainFinderService.tryFindById(chainId);
        if (optionalChain.isPresent()) {
            Chain chain = optionalChain.get();

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

        return optionalChain;
    }

    public Map<String, String> provideNavigationPath(String chainId) {
        Chain chain = chainFinderService.findById(chainId);
        return chain.getAncestors();
    }

    public Map<String, String> getNamesMapByChainIds(Set<String> chainIds) {
        return chainRepository.findAllById(chainIds).stream()
                .collect(Collectors.toMap(AbstractEntity::getId, AbstractEntity::getName));
    }

    public List<Chain> findByRequest(ListFolderRequest request) {
        Specification<Chain> specification = (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
        if (StringUtils.isNotBlank(request.getSearchString())) {
            specification = specification.and(chainFilterSpecificationBuilder.buildSearch(request.getSearchString()));
        }
        if (!request.getFilters().isEmpty()) {
            specification = specification.and(chainFilterSpecificationBuilder.buildFilter(request.getFilters()));
        }
        specification = specification.and((root, query, criteriaBuilder) ->
                isNull(request.getFolderId())
                        ? criteriaBuilder.isNull(root.get("parentFolder").get("id"))
                        : criteriaBuilder.equal(root.get("parentFolder").get("id"), request.getFolderId())
        );

        List<Chain> chains = chainRepository.findAll(specification);
        return applyComplexFilters(chains, request.getFilters());
    }

    public List<Chain> searchChains(ChainSearchRequestDTO searchRequestDTO) {
        Specification<Chain> specification = chainFilterSpecificationBuilder.buildSearch(searchRequestDTO.getSearchCondition());
        return chainRepository.findAll(specification);
    }

    public List<Chain> findByFilterRequest(List<FilterRequestDTO> filters) {
        // TODO pagination
        Specification<Chain> specification = chainFilterSpecificationBuilder.buildFilter(filters);
        List<Chain> chains = chainRepository.findAll(specification);

        return applyComplexFilters(chains, filters);
    }

    public List<Chain> applyComplexFilters(List<Chain> chains, List<FilterRequestDTO> filters) {

        chains = new ChainStatusFilters(runtimeDeploymentService).apply(chains, filters);
        chains = new ElementFilter().apply(chains, filters);
        chains = new LoggingFilter(chainRuntimePropertiesService).apply(chains, filters);

        return chains;
    }

    public void update(Chain chain) {
        chainRepository.save(chain);
        logChainAction(chain, LogOperation.UPDATE);
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

    public Chain save(Chain chain) {
        return save(chain, null);
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

    public List<UsedSystem> getUsedSystemIdsByChainIds(List<String> chainIds) {
        if (CollectionUtils.isEmpty(chainIds)) {
            return elementService.getAllUsedSystemIds();
        }
        return elementService.getUsedSystemIdsByChainIds(chainIds);
    }

    public Chain move(String chainId, String targetFolderId) {
        Chain chain = chainFinderService.findById(chainId);
        chain.setParentFolder(folderService.findEntityByIdOrNull(targetFolderId));
        logChainAction(chain, LogOperation.MOVE);
        return chain;
    }

    public Chain copy(String chainId, String targetFolderId) {
        return copy(chainFinderService.findById(chainId), folderService.findEntityByIdOrNull(targetFolderId));
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
                .map(label -> new ChainLabel(label.getName(), chainCopy))
                .collect(Collectors.toSet());
        chainCopy.setLabels(chainLabelsCopy);

        chainCopy.getDependencies().forEach(dependencyRepository::saveEntity);
        chainCopy.getMaskedFields().forEach(maskedFieldRepository::saveEntity);
        chainCopy.getElements().forEach(elementRepository::saveEntity);
        chainRepository.saveEntity(chainCopy);
        return chainCopy;
    }

    public Chain duplicate(String chainId) {
        Chain chain = chainFinderService.findById(chainId);
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
            if (element.getCreatedWhen() == null) {
                elementsModifiedState.add(element.getId());
            }
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

    public List<Chain> findAllChainsInFolders(List<String> folderIds) {
        return chainRepository.findAllChainsInFolders(folderIds);
    }

    public long getChainsCount() {
        return chainRepository.count();
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
}
