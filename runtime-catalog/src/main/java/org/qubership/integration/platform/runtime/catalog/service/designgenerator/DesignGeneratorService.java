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

package org.qubership.integration.platform.runtime.catalog.service.designgenerator;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramLangType;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramMode;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.ElementsSequenceDiagram;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementType;
import org.qubership.integration.platform.runtime.catalog.model.library.chaindesign.ContainerChildrenParameters;
import org.qubership.integration.platform.runtime.catalog.model.library.chaindesign.ElementContainerDesignParameters;
import org.qubership.integration.platform.runtime.catalog.model.library.chaindesign.ElementDesignParameters;
import org.qubership.integration.platform.runtime.catalog.model.library.chaindesign.ElementDiagramOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.DependencyService;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.interfaces.ContainerDesignProcessor;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.interfaces.DesignProcessor;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.util.DiagramBuilderEscapeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.DEFAULT_RESPONSE_TITLE;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.GROUP_BG_RGB;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.*;

@Slf4j
@Service
public class DesignGeneratorService {
    // exclude elements (not containers) from simple diagram
    private static final Set<String> SIMPLE_DIAGRAM_ELEMENT_EXCLUDE_SET = Set.of(
            "file-read", "file-write", "sftp-trigger-2", "sftp-trigger", "sftp-download", "sftp-upload",
            "log-record", "header-modification", "mapper", "mapper-2", "script", "xslt"
    );

    // <element_type, processor>
    private final Map<String, DesignProcessor> designProcessors = new HashMap<>();

    private final ElementService elementService;
    private final DependencyService dependencyService;
    private final LibraryElementsService libraryService;
    private final ChainFinderService chainFinderService;

    @Autowired
    public DesignGeneratorService(ElementService elementService,
                                  DependencyService dependencyService,
                                  LibraryElementsService libraryService,
                                  ChainFinderService chainFinderService,
                                  List<DesignProcessor> processors) {
        this.elementService = elementService;
        this.dependencyService = dependencyService;
        this.libraryService = libraryService;
        this.chainFinderService = chainFinderService;
        for (DesignProcessor processor : processors) {
            for (String supportedElementType : processor.supportedElementTypes()) {
                designProcessors.put(supportedElementType, processor);
            }
        }
    }

    public Map<DiagramMode, ElementsSequenceDiagram> generateChainSequenceDiagram(String chainId, List<DiagramMode> modes) {
        List<ChainElement> elements = elementService.findAllByChainId(chainId);
        List<Dependency> dependencies = dependencyService.findAllByElementsIDs(
                elements.stream().map(AbstractEntity::getId).collect(Collectors.toList()));

        return generateSequenceDiagrams(chainId, null, elements, dependencies, modes);
    }

    public Map<DiagramMode, ElementsSequenceDiagram> generateSnapshotSequenceDiagram(String chainId, String snapshotId, List<DiagramMode> modes) {
        List<ChainElement> elements = elementService.findAllBySnapshotId(snapshotId);
        List<Dependency> dependencies = dependencyService.findAllByElementsIDs(
                elements.stream().map(AbstractEntity::getId).collect(Collectors.toList()));

        return generateSequenceDiagrams(chainId, snapshotId, elements, dependencies, modes);
    }

    private Map<DiagramMode, ElementsSequenceDiagram> generateSequenceDiagrams(String chainId, String snapshotId, List<ChainElement> elements,
                                                                               List<Dependency> dependencies, List<DiagramMode> modes) {
        Map<DiagramMode, ElementsSequenceDiagram> result = new HashMap<>();
        for (DiagramMode mode : modes) {
            result.put(
                    mode,
                    ElementsSequenceDiagram.builder()
                            .chainId(chainId)
                            .snapshotId(snapshotId)
                            .diagramSources(generateSequenceDiagram(chainId, elements, dependencies, mode))
                            .build()
            );
        }
        return result;
    }

    private Map<DiagramLangType, String> generateSequenceDiagram(String chainId, List<ChainElement> elements,
                                                                 List<Dependency> dependencies, DiagramMode mode) {

        // <fromElementId, elementTo>
        Map<String, List<ChainElement>> fromElementMap = dependencies.stream()
                .collect(Collectors.groupingBy(e -> e.getElementFrom().getId(), Collectors.mapping(Dependency::getElementTo, Collectors.toList())));
        collectReuseDependencies(elements, fromElementMap);

        SequenceDiagramBuilder builder = new SequenceDiagramBuilder();
        Set<String> addedElementsIds = new HashSet<>();

        builder.append(DOCUMENT_START).append(AUTONUMBER);
        builder.append(BLOCK_DELIMITER);

        List<ChainElement> triggers = elements.stream()
                .filter(chainElement ->
                        libraryService.getElementDescriptor(chainElement) != null
                                && libraryService.getElementDescriptor(chainElement).getType() == ElementType.TRIGGER)
                .sorted(Comparator.comparing(AbstractEntity::getName))
                .collect(Collectors.toList());

        addParticipants(chainId, builder, triggers, fromElementMap, addedElementsIds, mode);

        for (ChainElement trigger : triggers) {
            String refChainId = DiagramBuilderEscapeUtil.removeOrReplaceUnsupportedCharacters(chainId);

            builder.append(BLOCK_DELIMITER);

            builder.append(START_GROUP, trigger.getName()); // plantuml
            builder.append(START_COLORED_GROUP, GROUP_BG_RGB[0], GROUP_BG_RGB[1], GROUP_BG_RGB[2], refChainId, trigger.getName()); // mermaid

            builder.append(ACTIVATE, refChainId);
            generateDiagramRecursive(refChainId, builder, trigger, fromElementMap, addedElementsIds, mode);
            builder.append(DEACTIVATE, refChainId);
            builder.append(END);

            builder.append(BLOCK_DELIMITER);
        }

        builder.append(BLOCK_DELIMITER);
        builder.append(DOCUMENT_END);

        return builder.build();
    }

    private void addParticipants(String chainId,
                                 SequenceDiagramBuilder builder,
                                 List<ChainElement> triggers,
                                 Map<String, List<ChainElement>> fromElementMap,
                                 Set<String> addedElementsIds,
                                 DiagramMode mode) {
        Map<String, String> participants = new LinkedHashMap<>();

        participants.put(DiagramBuilderEscapeUtil.removeOrReplaceUnsupportedCharacters(chainId),
                "QIP chain: " + chainFinderService.findById(chainId).getName());

        for (ChainElement trigger : triggers) {
            addedElementsIds.add(trigger.getId());
            addParticipant(chainId, participants, trigger);
        }

        for (ChainElement trigger : triggers) {
            for (ChainElement nextElement : getNextElements(trigger, fromElementMap)) {
                addParticipantsRecursive(chainId, participants, nextElement, fromElementMap, addedElementsIds, mode);
            }
        }

        for (Map.Entry<String, String> entry : participants.entrySet()) {
            builder.append(PARTICIPANT_AS, entry.getKey(), entry.getValue());
        }
    }

    private void addParticipantsRecursive(String chainId,
                                          Map<String, String> participants,
                                          ChainElement currentElement,
                                          Map<String, List<ChainElement>> fromElementMap,
                                          Set<String> addedElementsIds,
                                          DiagramMode mode) {
        if (currentElement == null) {
            return;
        }

        if (!addedElementsIds.contains(currentElement.getId())) {
            addedElementsIds.add(currentElement.getId());

            if (shouldWriteElement(currentElement, mode)) {
                addParticipant(chainId, participants, currentElement);
            }

            if (currentElement instanceof ContainerChainElement) {
                for (ChainElement innerElement : ((ContainerChainElement) currentElement).getElements()) {
                    if (innerElement.getInputDependencies().isEmpty()) {
                        addParticipantsRecursive(chainId, participants, innerElement, fromElementMap, addedElementsIds, mode);
                    }
                }
            }

            for (ChainElement nextElement : getNextElements(currentElement, fromElementMap)) {
                if (!addedElementsIds.contains(nextElement.getId())) {
                    addParticipantsRecursive(chainId, participants, nextElement, fromElementMap, addedElementsIds, mode);
                }
            }
        }
    }

    private List<ChainElement> getNextElements(ChainElement currentElement, Map<String, List<ChainElement>> fromElementMap) {
        return fromElementMap.getOrDefault(currentElement.getId(), Collections.emptyList());
    }

    private void addParticipant(String chainId, Map<String, String> participants, ChainElement element) {
        ElementDesignParameters designParameters = libraryService.getElementDescriptor(element).getDesignParameters();
        DesignProcessor designProcessor = designProcessors.get(element.getType());
        String participantName, participantId;

        if (designParameters == null) {
            if (designProcessor == null) {
                return;
            }
            participantName = designProcessor.getExternalParticipantName(element);
            participantId = designProcessor.getExternalParticipantId(element);
        } else {
            participantName = designParameters.getExternalParticipantName(chainId, element);
            participantId = designParameters.getExternalParticipantId(chainId, element);
        }

        if (participantName != null) {
            participants.put(participantId, participantName);
        }
    }

    private void generateDiagramRecursive(String refChainId,
                                          SequenceDiagramBuilder builder,
                                          ChainElement currentElement,
                                          Map<String, List<ChainElement>> fromElementMap,
                                          Set<String> elementsToProcessIds,
                                          DiagramMode mode) {
        if (currentElement == null) {
            return;
        }

        List<ChainElement> elementsTo = fromElementMap.getOrDefault(currentElement.getId(), Collections.emptyList());
        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(currentElement);
        DesignProcessor designProcessor = designProcessors.get(currentElement.getType());

        if (elementsToProcessIds.contains(currentElement.getId())) {
            elementsToProcessIds.remove(currentElement.getId());

            if (elementDescriptor.isContainer()) {
                processContainerElement(refChainId, builder, (ContainerChainElement) currentElement, fromElementMap,
                        elementsToProcessIds, mode, elementDescriptor, designProcessor, elementsTo);
            } else {
                processElement(refChainId, builder, currentElement, fromElementMap, elementsToProcessIds,
                        mode, elementDescriptor, elementsTo, designProcessor);
            }
        }
    }

    private void processContainerElement(String refChainId, SequenceDiagramBuilder builder, ContainerChainElement currentElement,
                                         Map<String, List<ChainElement>> fromElementMap, Set<String> elementsToProcessIds,
                                         DiagramMode mode, ElementDescriptor elementDescriptor,
                                         DesignProcessor designProcessor, List<ChainElement> elementsTo
    ) {
        if (ElementType.REUSE == elementDescriptor.getType()) {
            currentElement.getElements().stream()
                    .filter(child -> child.getInputDependencies().isEmpty())
                    .forEach(child -> generateDiagramRecursive(refChainId, builder, child, fromElementMap, elementsToProcessIds, mode));
            return;
        }

        ElementContainerDesignParameters designParameters = elementDescriptor.getDesignContainerParameters();

        if (designParameters == null) {
            if (designProcessor instanceof ContainerDesignProcessor containerProcessor) {
                processContainerWithDesignProcessor(refChainId, builder, currentElement, fromElementMap, elementsToProcessIds, mode, elementsTo, containerProcessor);
            }
        } else {
            processContainerWithDesignParams(refChainId, builder, currentElement, fromElementMap, elementsToProcessIds, mode, elementsTo, designParameters);
        }
    }

    private void processContainerWithDesignProcessor(String refChainId, SequenceDiagramBuilder builder, ContainerChainElement currentElement,
                                                     Map<String, List<ChainElement>> fromElementMap, Set<String> elementsToProcessIds,
                                                     DiagramMode mode, List<ChainElement> elementsTo,
                                                     ContainerDesignProcessor containerProcessor) {
        List<ChainElement> sortedChildren = currentElement.getElements().stream()
                .filter(containerProcessor.getChildrenFilter())
                .sorted(containerProcessor.getComparator())
                .toList();

        containerProcessor.processBefore(refChainId, builder, currentElement);

        for (ChainElement child : sortedChildren) {
            if (!containerProcessor.isContainerWithRestrictions()) {
                containerProcessor.processChildBefore(refChainId, builder, currentElement, child);
                generateDiagramRecursive(refChainId, builder, child, fromElementMap, elementsToProcessIds, mode);
                containerProcessor.processChildAfter(refChainId, builder, currentElement, child);
                continue;
            }

            containerProcessor.processChildBefore(refChainId, builder, currentElement, child);
            if (child instanceof ContainerChainElement childContainer) {
                childContainer.getElements().stream()
                        .filter(element -> element.getInputDependencies().isEmpty())
                        .forEach(element -> generateDiagramRecursive(refChainId, builder, element, fromElementMap, elementsToProcessIds, mode));
            } else {
                List<ChainElement> childElementsTo = fromElementMap.getOrDefault(child.getId(), Collections.emptyList());
                toNextElements(refChainId, builder, fromElementMap, childElementsTo, elementsToProcessIds, mode);
            }
            containerProcessor.processChildAfter(refChainId, builder, currentElement, child);
        }

        containerProcessor.processAfter(refChainId, builder, currentElement);
        toNextElements(refChainId, builder, fromElementMap, elementsTo, elementsToProcessIds, mode);
    }

    private void processContainerWithDesignParams(String refChainId, SequenceDiagramBuilder builder,
                                                  ContainerChainElement currentElement, Map<String, List<ChainElement>> fromElementMap,
                                                  Set<String> elementsToProcessIds, DiagramMode mode,
                                                  List<ChainElement> elementsTo, ElementContainerDesignParameters designParameters) {
        List<ElementDiagramOperation> endOperations = designParameters.getEndOperations();
        List<ContainerChildrenParameters> childrenParameters = designParameters.getChildren();

        // <element_type, list<elements>>
        Map<String, List<ChainElement>> innerElementsMap = currentElement.getElements().stream()
                .collect(Collectors.groupingBy(ChainElement::getType, Collectors.mapping(Function.identity(), Collectors.toList())));

        boolean firstChildrenDetected = false;
        boolean atLeastOneChildHasDependency = false;
        for (ContainerChildrenParameters childrenParams : childrenParameters) {
            List<ChainElement> children = innerElementsMap.getOrDefault(childrenParams.getName(), Collections.emptyList());
            boolean firstElementDetected = false;
            for (ChainElement child : children) {
                boolean childHasElements;
                Runnable nextElementsFunction;
                if (child instanceof ContainerChainElement childContainer) {
                    childHasElements = !childContainer.getElements().isEmpty();
                    List<ChainElement> startElements = childContainer.getElements().stream()
                            .filter(element -> element.getInputDependencies().isEmpty())
                            .toList();
                    nextElementsFunction = () -> startElements
                            .forEach(startElement ->
                                    generateDiagramRecursive(refChainId, builder, startElement, fromElementMap, elementsToProcessIds, mode));
                } else {
                    List<ChainElement> childElementsTo = fromElementMap.getOrDefault(child.getId(), Collections.emptyList());
                    childHasElements = !childElementsTo.isEmpty();
                    nextElementsFunction = () ->
                            toNextElements(refChainId, builder, fromElementMap, childElementsTo, elementsToProcessIds, mode);
                }

                if (childHasElements) {
                    atLeastOneChildHasDependency = true;

                    ElementDiagramOperation operation;
                    if (!firstElementDetected || childrenParams.getSecondaryOperation() == null) {
                        operation = childrenParams.getPrimaryOperation();
                        if (designParameters.getFirstChildrenType() != null && !firstChildrenDetected) {
                            operation.setType(designParameters.getFirstChildrenType());
                        } else if (designParameters.getChildrenType() != null) {
                            operation.setType(designParameters.getChildrenType());
                        }
                        firstChildrenDetected = true;
                        firstElementDetected = true;
                    } else {
                        operation = childrenParams.getSecondaryOperation();
                    }

                    List<String> argsList = new ArrayList<>();
                    for (String arg : operation.getArgs()) {
                        argsList.add(DiagramBuilderEscapeUtil.substituteProperties(refChainId, child, arg));
                    }
                    builder.append(operation.getType(), argsList.toArray(new String[0]));

                    nextElementsFunction.run();
                }
            }
        }

        if (atLeastOneChildHasDependency) {
            for (ElementDiagramOperation endOperation : endOperations) {
                builder.append(endOperation.getType(),
                        DiagramBuilderEscapeUtil.substituteReferences(refChainId, currentElement, endOperation.getArgs()));
            }
        }

        toNextElements(refChainId, builder, fromElementMap, elementsTo, elementsToProcessIds, mode);
    }

    private void processElement(String refChainId, SequenceDiagramBuilder builder, ChainElement currentElement,
                                Map<String, List<ChainElement>> fromElementMap, Set<String> elementsToProcessIds,
                                DiagramMode mode, ElementDescriptor elementDescriptor,
                                List<ChainElement> elementsTo, DesignProcessor designProcessor
    ) {
        if (ElementType.REUSE_REFERENCE == elementDescriptor.getType()) {
            toNextElements(refChainId, builder, fromElementMap, elementsTo, elementsToProcessIds, mode);
            return;
        }

        boolean shouldWriteElement = shouldWriteElement(currentElement, mode);

        ElementDesignParameters designParameters = elementDescriptor.getDesignParameters();

        if (designParameters == null) {
            processElementWithDesignProcessor(refChainId, builder, currentElement, fromElementMap,
                    elementsToProcessIds, mode, elementsTo, designProcessor, shouldWriteElement);
        } else {
            processElementWithDesignParams(refChainId, builder, currentElement, fromElementMap, elementsToProcessIds,
                    mode, elementsTo, designParameters, shouldWriteElement);
        }
    }

    private void processElementWithDesignProcessor(String refChainId, SequenceDiagramBuilder builder, ChainElement currentElement,
                                                   Map<String, List<ChainElement>> fromElementMap, Set<String> elementsToProcessIds,
                                                   DiagramMode mode, List<ChainElement> elementsTo,
                                                   DesignProcessor designProcessor, boolean shouldWriteElement) {
        if (designProcessor != null) {
            if (shouldWriteElement) {
                designProcessor.processBefore(refChainId, builder, currentElement);
                toNextElements(refChainId, builder, fromElementMap, elementsTo, elementsToProcessIds, mode);
                designProcessor.processAfter(refChainId, builder, currentElement);
            } else {
                toNextElements(refChainId, builder, fromElementMap, elementsTo, elementsToProcessIds, mode);
            }
        }
    }

    private void processElementWithDesignParams(String refChainId, SequenceDiagramBuilder builder, ChainElement currentElement,
                                                Map<String, List<ChainElement>> fromElementMap, Set<String> elementsToProcessIds,
                                                DiagramMode mode, List<ChainElement> elementsTo,
                                                ElementDesignParameters designParameters, boolean shouldWriteElement) {
        String fromId, toId, title = designParameters.getRequestLineTitle(refChainId, currentElement);
        if (designParameters.isDirectionToChain()) {
            fromId = designParameters.getExternalParticipantId(refChainId, currentElement);
            toId = refChainId;
        } else {
            fromId = refChainId;
            toId = designParameters.getExternalParticipantId(refChainId, currentElement);
        }

        if (shouldWriteElement) {
            builder.append(LINE_WITH_ARROW_SOLID_RIGHT, fromId, toId, title);

            if (designParameters.isHasResponse()) {
                builder.append(ACTIVATE, toId);

                if (!designParameters.isResponseAfterRequest()) {
                    toNextElements(refChainId, builder, fromElementMap, elementsTo, elementsToProcessIds, mode);
                }

                builder.append(LINE_WITH_ARROW_DOTTED_RIGHT, toId, fromId, DEFAULT_RESPONSE_TITLE);
                if (!designParameters.isDirectionToChain()) {
                    builder.append(DEACTIVATE, toId);
                }
            }
        } else {
            if (designParameters.isHasResponse()) {
                if (!designParameters.isResponseAfterRequest()) {
                    toNextElements(refChainId, builder, fromElementMap, elementsTo, elementsToProcessIds, mode);
                }
            }
        }

        if (designParameters.isResponseAfterRequest()) {
            toNextElements(refChainId, builder, fromElementMap, elementsTo, elementsToProcessIds, mode);
        }
    }

    private void toNextElements(String refChainId,
                                SequenceDiagramBuilder builder,
                                Map<String, List<ChainElement>> fromElementMap,
                                List<ChainElement> elementsTo,
                                Set<String> elementsToProcessIds,
                                DiagramMode mode) {
        for (ChainElement elementTo : elementsTo) {
            generateDiagramRecursive(refChainId, builder, elementTo, fromElementMap, elementsToProcessIds, mode);
        }
    }

    private void collectReuseDependencies(List<ChainElement> elements, Map<String, List<ChainElement>> fromElementMap) {
        Map<String, ChainElement> elementMap = elements.stream()
                .collect(Collectors.toMap(
                        element -> element.getSnapshot() != null ? element.getOriginalId() : element.getId(),
                        Function.identity())
                );
        for (ChainElement element : elements) {
            ElementDescriptor descriptor = libraryService.getElementDescriptor(element);
            if (ElementType.REUSE_REFERENCE != descriptor.getType()) {
                continue;
            }
            ChainElement reuseElement = elementMap.get(element.getPropertyAsString(descriptor.getReuseReferenceProperty()));
            if (reuseElement instanceof ContainerChainElement reuseContainer) {
                if (!element.getOutputDependencies().isEmpty()) {
                    fromElementMap.remove(element.getId());
                    List<ChainElement> referenceOutputElements = element.getOutputDependencies().stream()
                            .map(Dependency::getElementTo)
                            .toList();
                    reuseContainer.getElements().stream()
                            .filter(child -> child.getOutputDependencies().isEmpty())
                            .forEach(lastElement -> fromElementMap.put(lastElement.getId(), referenceOutputElements));
                }

                fromElementMap.compute(element.getId(), (elementFormId, elementsTo) -> {
                    List<ChainElement> result = new ArrayList<>(Collections.singleton(reuseContainer));
                    if (elementsTo != null) {
                        elementsTo.stream()
                                .filter(elementTo -> StringUtils.equals(elementTo.getId(), element.getId()))
                                .forEach(result::add);
                    }
                    return result;
                });
            }
        }
    }

    private static boolean shouldWriteElement(ChainElement currentElement, DiagramMode mode) {
        return !(mode == DiagramMode.SIMPLE
                && SIMPLE_DIAGRAM_ELEMENT_EXCLUDE_SET.contains(currentElement.getType()));
    }
}
