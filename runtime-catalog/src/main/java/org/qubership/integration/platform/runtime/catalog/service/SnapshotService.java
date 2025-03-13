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

import org.qubership.integration.platform.runtime.catalog.builder.XmlBuilder;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.verification.ElementPropertiesVerificationService;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.VerificationError;
import org.qubership.integration.platform.catalog.context.RequestIdContext;
import org.qubership.integration.platform.catalog.exception.SnapshotCreationException;
import org.qubership.integration.platform.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.catalog.persistence.configs.entity.AbstractLabel;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.DependencyRepository;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.SnapshotLabelsRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Period;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class SnapshotService {

    private static final String CONFIGURATION_WITH_ID_NOT_FOUND_MESSAGE = "Can't find configuration with id ";

    private final SnapshotRepository snapshotRepository;
    private final ElementRepository elementRepository;
    private final ElementService elementService;
    private final XmlBuilder xmlBuilder;
    private final ChainService chainService;
    private final DependencyRepository dependencyRepository;
    private final DeploymentService deploymentService;
    private final ActionsLogService actionLogger;
    private final ElementPropertiesVerificationService elementPropertiesVerificationService;
    private final MaskedFieldsService maskedFieldsService;
    private final TransactionHandler transactionHandler;
    private final SnapshotService self;
    private final SnapshotLabelsRepository snapshotLabelsRepository;

    @Autowired
    public SnapshotService(SnapshotRepository snapshotRepository,
                           ElementRepository elementRepository,
                           ElementService elementService,
                           XmlBuilder xmlBuilder,
                           ChainService chainService,
                           DependencyRepository dependencyRepository,
                           @Lazy DeploymentService deploymentService,
                           @Lazy SnapshotService self,
                           ActionsLogService actionLogger,
                           ElementPropertiesVerificationService elementPropertiesVerificationService,
                           MaskedFieldsService maskedFieldsService,
                           TransactionHandler  transactionHandler,
                           SnapshotLabelsRepository snapshotLabelsRepository) {
        this.snapshotRepository = snapshotRepository;
        this.elementRepository = elementRepository;
        this.elementService = elementService;
        this.xmlBuilder = xmlBuilder;
        this.chainService = chainService;
        this.dependencyRepository = dependencyRepository;
        this.deploymentService = deploymentService;
        this.actionLogger = actionLogger;
        this.elementPropertiesVerificationService = elementPropertiesVerificationService;
        this.maskedFieldsService = maskedFieldsService;
        this.transactionHandler = transactionHandler;
        this.self = self;
        this.snapshotLabelsRepository = snapshotLabelsRepository;
    }

    public Snapshot findById(String snapshotId) {
        return snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new EntityNotFoundException(CONFIGURATION_WITH_ID_NOT_FOUND_MESSAGE + snapshotId));
    }

    // Map<chainId, snapshot>
    public Map<String, Snapshot> findLastCreatedOrBuild(Collection<String> chainIds, BiConsumer<String, String> errorHandler) {
        Map<String, Snapshot> snapshots = snapshotRepository.findAllLastCreated(chainIds).stream()
                .collect(Collectors.toMap(snapshot -> snapshot.getChain().getId(), Function.identity()));
        final Set<String> chainsWithoutSnapshot = new HashSet<>(chainIds);
        chainsWithoutSnapshot.removeAll(snapshots.keySet());

        // create a new snapshot if there are no snapshots in the chain
        snapshots.putAll(buildAll(chainsWithoutSnapshot, errorHandler));

        return snapshots;
    }

    public Optional<Snapshot> tryFindById(String snapshotId) {
        return snapshotRepository.findById(snapshotId);
    }

    public List<Snapshot> findByChainIdLight(String chainId) {
        return snapshotRepository.findAllByChainId(chainId);
    }

    // Map<chainId, snapshot>
    public Map<String, Snapshot> buildAll(Collection<String> chainIds, BiConsumer<String, String> errorHandler) {
        Map<String, Snapshot> result = new HashMap<>();
        for (String chainId : chainIds) {
            try {
                result.put(chainId, self.build(chainId));
            } catch (Exception e) {
                log.warn("Failed to build snapshot for chainId {}: {}", chainId, e.getMessage());
                errorHandler.accept(chainId, e.getMessage());
            }
        }
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Snapshot build(String chainId) {
        return build(chainId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Snapshot build(String chainId, Set<String> technicalLabels) {
        Chain chain = chainService.findById(chainId);
        verifyElementProperties(chain);

        String name = snapshotRepository.getNextAvailableName(chainId);

        Snapshot snapshot = Snapshot.builder()
                        .name(name)
                        .chain(chain)
                        .build();
        if (CollectionUtils.isNotEmpty(technicalLabels)) {
            snapshot.addLabels(getSnapshotTechnicalLabels(technicalLabels, snapshot));
        }

        snapshot = snapshotRepository.saveAndFlush(snapshot);

        moveElementsToSnapshot(chain, snapshot);
        moveMaskedFields(chain.getMaskedFields(), snapshot);
        List<ChainElement> snapshotElements = snapshot.getElements();
        fillServiceEnvironments(snapshotElements);

        try {
            snapshot.setXmlDefinition(xmlBuilder.build(snapshotElements));
        } catch (Exception e) {
            log.error("Failed to build xml configuration: {}", e.getMessage());
            throw (e instanceof RuntimeException) ?
                    (RuntimeException) e : new RuntimeException("Failed to build xml configuration", e);
        }
        chainService.setCurrentSnapshot(chain.getId(), snapshot);

        logSnapshotAction(snapshot, chain, LogOperation.CREATE);

        return snapshot;
    }

    private Collection<SnapshotLabel> getSnapshotTechnicalLabels(Set<String> technicalLabels, Snapshot snapshot) {
        List<SnapshotLabel> snapshotLabels = new ArrayList<>();
        for (String labelName : technicalLabels) {
            snapshotLabels.add(new SnapshotLabel(labelName, snapshot, true));
        }
        return snapshotLabels;
    }

    private void fillServiceEnvironments(List<ChainElement> newElements) {
        elementService.fillElementsEnvironment(newElements);
        elementRepository.saveAll(newElements);
    }

    private void verifyElementProperties(Chain chain) {
        Map<ChainElement, Collection<VerificationError>> errorMap =
                elementPropertiesVerificationService.verifyElementProperties(chain);
        if (!errorMap.isEmpty()) {
            errorMap.forEach((element, errors) -> errors.forEach(
                    error -> log.error("Chain '{}' ({}), element '{}' ({}) properties verification error: {}",
                            chain.getName(), chain.getId(), element.getName(), element.getId(), error.message()))
            );
            Map.Entry<ChainElement, Collection<VerificationError>> entry = errorMap.entrySet().iterator().next();
            ChainElement element = entry.getKey();
            String message = entry.getValue().stream().findFirst().map(VerificationError::message).orElse("");
            throw new SnapshotCreationException(message, element);
        }
    }

    public Snapshot revert(String chainId, String snapshotId) {
        elementService.deleteAllByChainIdAndFlush(chainId);
        maskedFieldsService.deleteAllByChainIdAndFlush(chainId);
        Chain chain = chainService.findById(chainId);
        Snapshot snapshot = findById(snapshotId);
        revertElements(snapshot, chain);
        revertMaskedFields(snapshot.getMaskedFields(), chain);
        chain.setCurrentSnapshot(snapshot);
        chain.setUnsavedChanges(false);

        logSnapshotAction(snapshot, chain, LogOperation.REVERT);

        return snapshot;
    }

    private void revertElements(Snapshot snapshot, Chain chain) {
        Map<ChainElement, ChainElement> replacements = new HashMap<>();
        for (ChainElement element : snapshot.getElements()) {
            ChainElement newElement = element.copy();
            newElement.setId(element.getOriginalId());
            newElement.setOriginalId(null);
            newElement.setSnapshot(null);
            newElement = elementRepository.save(newElement);

            chain.addElement(newElement);
            replacements.put(element, newElement);
        }
        chain.setDefaultSwimlane((SwimlaneChainElement) replacements.get(snapshot.getDefaultSwimlane()));
        chain.setReuseSwimlane((SwimlaneChainElement) replacements.get(snapshot.getReuseSwimlane()));
        replaceChildren(replacements);
        replaceDependencies(replacements);
    }

    private void revertMaskedFields(Set<MaskedField> maskedFields, Chain chain) {
        for (MaskedField maskedField : maskedFields) {
            MaskedField copiedMaskedField = maskedField.copy();
            copiedMaskedField.setChain(chain);
            copiedMaskedField = maskedFieldsService.save(copiedMaskedField);
            chain.addMaskedField(copiedMaskedField);
        }
    }

    private void moveMaskedFields(Set<MaskedField> maskedFields, Snapshot snapshot) {
        for (MaskedField maskedField : maskedFields) {
            MaskedField copiedMaskedField = maskedField.copy();
            copiedMaskedField.setSnapshot(snapshot);
            copiedMaskedField = maskedFieldsService.save(copiedMaskedField);
            snapshot.addMaskedField(copiedMaskedField);
        }
    }

    private void moveElementsToSnapshot(@NonNull Chain chain, Snapshot snapshot) {
        Map<ChainElement, ChainElement> replacements = copyElements(new ArrayList<>(chain.getElements()), null, snapshot);
        if (replacements.get(chain.getDefaultSwimlane()) instanceof SwimlaneChainElement defaultSwimalne) {
            snapshot.setDefaultSwimlane(defaultSwimalne);
        }
        if (replacements.get(chain.getReuseSwimlane()) instanceof SwimlaneChainElement reuseSwimlane) {
            snapshot.setReuseSwimlane(reuseSwimlane);
        }
        replaceChildren(replacements);
        replaceDependencies(replacements);
    }

    private Map<ChainElement, ChainElement> copyElements(List<ChainElement> elements, @Nullable Chain chain, Snapshot snapshot) {
        Map<ChainElement, ChainElement> replacements = new HashMap<>();
        for (ChainElement element : elements) {
            ChainElement newElement = element.copy();
            newElement.setSnapshot(snapshot);
            newElement.setChain(chain);
            newElement = elementRepository.save(newElement);

            if (snapshot != null) {
                snapshot.addElement(newElement);
            }

            if (chain != null) {
                chain.addElement(newElement);
            }
            replacements.put(element, newElement);
        }
        return replacements;
    }

    private void replaceChildren(Map<ChainElement, ChainElement> replacements) {
        for (var entry : replacements.entrySet()) {
            ChainElement element = entry.getKey();
            ChainElement newElement = entry.getValue();
            if (element instanceof ContainerChainElement container) {
                ContainerChainElement newContainer = (ContainerChainElement) newElement;
                for (ChainElement child : container.getElements()) {
                    ChainElement newChild = replacements.get(child);
                    newContainer.addChildElement(newChild);
                }
            }

            SwimlaneChainElement elementSwimlane = element.getSwimlane();
            if (elementSwimlane != null) {
                SwimlaneChainElement newElementSwimlane = (SwimlaneChainElement) replacements.get(elementSwimlane);
                newElementSwimlane.addElement(newElement);
            }
        }
    }

    private void replaceDependencies(Map<ChainElement, ChainElement> replacements) {
        Map<String, Dependency> dependencyReplacements = new HashMap<>();
        for (var entry : replacements.entrySet()) {
            ChainElement element = entry.getKey();
            ChainElement newElement = entry.getValue();
            for (Dependency dependency : element.getInputDependencies()) {
                if (!dependencyReplacements.containsKey(dependency.getId())) {
                    dependencyReplacements.put(dependency.getId(), createDependency(
                            replacements.get(dependency.getElementFrom()),
                            replacements.get(dependency.getElementTo())
                    ));
                }
                newElement.addInputDependency(dependencyReplacements.get(dependency.getId()));
            }
            for (Dependency dependency : element.getOutputDependencies()) {
                if (!dependencyReplacements.containsKey(dependency.getId())) {
                    dependencyReplacements.put(dependency.getId(), createDependency(
                            replacements.get(dependency.getElementFrom()),
                            replacements.get(dependency.getElementTo())
                    ));
                }
                newElement.addOutputDependency(dependencyReplacements.get(dependency.getId()));
            }
        }
    }

    private Dependency createDependency(ChainElement from, ChainElement to) {
        return dependencyRepository.save(Dependency.of(from, to));
    }

    public void deleteAllByChainId(String chainId) {
        List<Snapshot>  snapshots = findByChainIdLight(chainId);
        deploymentService.deleteAllByChainId(chainId);
        chainService.clearCurrentSnapshot(chainId);
        snapshotRepository.deleteAllByChainId(chainId);
        snapshots.forEach(snapshot -> {logSnapshotAction(snapshot,snapshot.getChain(),LogOperation.DELETE);});
    }

    public void deleteById(String snapshotId) {
        deploymentService.deleteAllBySnapshotId(snapshotId);
        Snapshot snapshot = findById(snapshotId);

        Chain chain = snapshot.getChain();
        if (chain.getCurrentSnapshot() != null) {
            if (chain.getCurrentSnapshot().getId().equals(snapshotId)){
                chain.setCurrentSnapshot(null);
            }
        }
        snapshotRepository.deleteById(snapshotId);
        logSnapshotAction(snapshot, chain, LogOperation.DELETE);
    }

    public Snapshot merge(String chainId, String snapshotId, Snapshot request) {
        Snapshot snapshot = findById(snapshotId);
        if (snapshot == null) {
            snapshot = build(chainId);
        }
        snapshot.setName(request.getName());
        replaceLabels(snapshot, request.getLabels());
        snapshot = snapshotRepository.save(snapshot);

        logSnapshotAction(snapshot, snapshot.getChain(), LogOperation.UPDATE);

        return snapshot;
    }

    private void replaceLabels(Snapshot snapshot, Set<SnapshotLabel> newLabels) {
        if (newLabels == null) {
            newLabels = Collections.emptySet();
        }
        Set<SnapshotLabel> finalNewLabels = newLabels;
        final Snapshot finalSnapshot = snapshot;

        finalNewLabels.forEach(label -> label.setSnapshot(snapshot));

        // Remove absent labels from db
        snapshot.getLabels().removeIf(l -> !l.isTechnical() && !finalNewLabels.stream().map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));
        // Add to database only missing labels
        finalNewLabels.removeIf(l -> l.isTechnical() || finalSnapshot.getLabels().stream().filter(lab -> !lab.isTechnical()).map(AbstractLabel::getName).collect(Collectors.toSet()).contains(l.getName()));

        snapshot.addLabels(newLabels);
    }

    private void logSnapshotAction(Snapshot snapshot, Chain chain, LogOperation operation) {
        logSnapshotAction(snapshot.getId(), snapshot.getName(), chain.getId(), chain.getName(), operation);
    }

    private void logSnapshotAction(String snapshotId, String snapshotName, String chainId, String chainName, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SNAPSHOT)
                .entityId(snapshotId)
                .entityName(snapshotName)
                .parentType(chainId == null ? null : EntityType.CHAIN)
                .parentId(chainId)
                .parentName(chainName)
                .operation(operation)
                .build());
    }

    public void pruneSnapshotsAsync(int olderThanDays, int chunk) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SNAPSHOT_CLEANUP)
                .operation(LogOperation.EXECUTE)
                .build());

        String requestId = RequestIdContext.get();
        CompletableFuture.runAsync(() -> {
            RequestIdContext.set(requestId);
            pruneSnapshots(olderThanDays, chunk);
        }).whenCompleteAsync((ignored, throwable) -> {
            RequestIdContext.set(requestId);
            if (throwable != null) {
                log.error("Exception during snapshot cleanup", throwable);
            }
        });
    }

    private void pruneSnapshots(int olderThanDays, int chunk) {
        long deletedTotal = 0;
        int deletedCurrent;

        long startTime = System.currentTimeMillis();
        Timestamp deletionDate = Timestamp.from(Instant.now().minus(Period.ofDays(olderThanDays)));

        do {
            deletedCurrent = transactionHandler.supplyInNewTransaction(() -> {
                List<Map<String, String>> result = snapshotRepository.pruneByCreatedWhen(deletionDate, chunk);
                result.forEach(s -> logSnapshotAction(
                        s.get(AbstractEntity.Fields.id), s.get(AbstractEntity.Fields.name), s.get(Snapshot.Fields.chain),
                        chainService.tryFindById(s.get(Snapshot.Fields.chain)).map(Chain::getName).orElse(null), // XXX Potential performance improvement
                        LogOperation.DELETE));
                return result.size();
            });
            deletedTotal += deletedCurrent;

            if (deletedCurrent > 0) {
                log.debug("Snapshots chunk of {} removed, currently removed {}", deletedCurrent, deletedTotal);
            }
        } while (deletedCurrent > 0);

        String durationStr = DurationFormatUtils.formatDurationWords(
                System.currentTimeMillis() - startTime, true, false);
        log.info("Snapshots removed successfully: {}. Time elapsed: {}", deletedTotal, durationStr);
    }
}
