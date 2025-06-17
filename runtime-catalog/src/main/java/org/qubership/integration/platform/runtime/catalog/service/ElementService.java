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
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.configuration.aspect.ChainModification;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementCreationException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementValidationException;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.model.ElementsWithSystemUsage;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.UsedSystem;
import org.qubership.integration.platform.runtime.catalog.model.library.*;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.CreateElementRequest;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.*;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.SPECIFICATION_ID;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.SYSTEM_ID;

@Slf4j
@Service
@Transactional
public class ElementService extends ElementBaseService {

    public static final String CONTAINER_TYPE_NAME = "container";
    public static final String CONTAINER_DEFAULT_NAME = "Container";
    private static final String GROUP_ID_PROPERTY = "groupId";
    public static final String SWIMLANE_TYPE_NAME = "swimlane";

    protected final AuditingHandler auditingHandler;
    protected final LibraryElementsService libraryService;
    protected final ChainFinderService chainFinderService;
    protected final SwimlaneService swimlaneService;
    protected final ActionsLogService actionLogger;
    protected final OrderedElementService orderedElementService;
    protected final ElementUtils elementUtils;
    protected final SystemEnvironmentsGenerator systemEnvironmentsGenerator;

    @Autowired
    public ElementService(
            ElementRepository elementRepository,
            AuditingHandler jpaAuditingHandler,
            LibraryElementsService libraryService,
            ChainFinderService chainFinderService,
            SwimlaneService swimlaneService,
            ActionsLogService actionLogger,
            OrderedElementService orderedElementService,
            ElementUtils elementUtils,
            SystemEnvironmentsGenerator systemEnvironmentsGenerator
    ) {
        super(elementRepository);
        this.auditingHandler = jpaAuditingHandler;
        this.libraryService = libraryService;
        this.chainFinderService = chainFinderService;
        this.swimlaneService = swimlaneService;
        this.actionLogger = actionLogger;
        this.orderedElementService = orderedElementService;
        this.elementUtils = elementUtils;
        this.systemEnvironmentsGenerator = systemEnvironmentsGenerator;
    }

    public List<ChainElement> findAllBySnapshotIdAndType(String snapshotId, String type) {
        return elementRepository.findAllBySnapshotIdAndType(snapshotId, type);
    }

    public void deleteAllByChainId(String chainId) {
        elementRepository.deleteAllByChainId(chainId);
    }

    public void deleteAllByChainIdAndFlush(String chainId) {
        deleteAllByChainId(chainId);
        elementRepository.flush();
    }

    public void fillElementsEnvironment(List<ChainElement> elements) {
        if (log.isDebugEnabled()) {
            log.debug("Fill Elements Environment request accepted {}",
                    elements.stream().map(ChainElement::getOriginalId).collect(Collectors.toList()));
        }
        HashMap<String, List<ChainElement>> elementsBySystemId = getElementsBySystemId(elements);
        if (elementsBySystemId.isEmpty()) {
            return;
        }

        List<ServiceEnvironment> environments = systemEnvironmentsGenerator.generateSystemEnvironments(elementsBySystemId.keySet());
        mergeElementsBySystemIdWithEnvironments(elementsBySystemId, environments);
    }

    public void mergeElementsBySystemIdWithEnvironments(HashMap<String, List<ChainElement>> elementsBySystemId,
                                                        List<ServiceEnvironment> environments) {
        for (ServiceEnvironment serviceEnvironment : environments) {
            List<ChainElement> elementsToUpdate = elementsBySystemId.get(serviceEnvironment.getSystemId());
            if (elementsToUpdate != null) {
                elementsToUpdate.forEach(element -> {
                    if (element.getProperties().containsKey(GROUP_ID_PROPERTY)) {
                        serviceEnvironment.getProperties().put(GROUP_ID_PROPERTY, element.getProperties().get(GROUP_ID_PROPERTY));
                    }
                    element.setEnvironment(serviceEnvironment.clone());
                });
            }
        }
    }

    public HashMap<String, List<ChainElement>> getElementsBySystemId(List<ChainElement> elements) {
        HashMap<String, List<ChainElement>> elementsBySystemId = new HashMap<>();
        for (ChainElement element : elements) {
            String systemId;
            switch (element.getType()) {
                case SERVICE_CALL_COMPONENT:
                case ASYNC_API_TRIGGER_COMPONENT:
                case HTTP_TRIGGER_COMPONENT:
                    systemId = element.getProperties() == null ? null
                           : (String) element.getProperties().get(SYSTEM_ID);
                    break;
                default:
                    continue;
            }
            if (StringUtils.isEmpty(systemId)) {
                continue;
            }

            if (!elementsBySystemId.containsKey(systemId)) {
                elementsBySystemId.put(systemId, new ArrayList<>());
            }
            elementsBySystemId.get(systemId).add(element);
        }
        return elementsBySystemId;
    }

    public void setActualizedChainElements(List<ChainElement> oldChainElementStates, List<ChainElement> newChainElementStates) {
        //We must actualize states of non container elements before
        elementRepository.actualizeCollectionStateWOUpdates(getAllChildElements(oldChainElementStates), getAllChildElements(newChainElementStates));
        elementRepository.actualizeCollectionStateWOUpdates(getAllParentElements(oldChainElementStates), getAllParentElements(newChainElementStates));
        //Merge (updates) will persist child entities too, so we need to do it as a last step
        elementRepository.actualizeCollectionStateOnlyUpdates(getAllChildElements(oldChainElementStates), getAllChildElements(newChainElementStates));
        elementRepository.actualizeCollectionStateOnlyUpdates(getAllParentElements(oldChainElementStates), getAllParentElements(newChainElementStates));
    }

    public List<ChainElement> findAllByChainId(String chainId) {
        var chain = chainFinderService.findById(chainId);
        return chain.getElements();
    }

    public List<ChainElement> findAllBySnapshotId(String snapshotId) {
        return elementRepository.findAllBySnapshotId(snapshotId);
    }

    public List<Pair<String, ChainElement>> findAllElementsWithChainNameByElementType(String type) {
        return elementRepository.findAllByTypeInAndChainNotNull(Collections.singletonList(type))
                .stream()
                .map(element -> Pair.of(element.getChain().getName(), element))
                .collect(Collectors.toList());
    }

    public Optional<ChainElement> findByOriginalId(String originalId) {
        return elementRepository.findByOriginalId(originalId);
    }

    public List<ChainElement> findAllById(List<String> elementIds) {
        return elementRepository.findAllById(elementIds);
    }

    public Optional<ChainElement> findByIdAndChainId(String elementId, String chainId) {
        return Optional.ofNullable(elementRepository.findByIdAndChainId(elementId, chainId));
    }

    public Optional<SwimlaneChainElement> findSwimlaneWithLockingById(String swimlaneId) {
        return elementRepository.findSwimlaneWithLockingById(swimlaneId);
    }

    public Optional<SwimlaneChainElement> findDefaultSwimlaneWithLockingByChainId(String chainId) {
        return elementRepository.findDefaultSwimlaneWithLockingByChainId(chainId);
    }

    public Optional<SwimlaneChainElement> findReuseSwimlaneWithLockingByChainId(String chainId) {
        return elementRepository.findReuseSwimlaneWithLockingByChainId(chainId);
    }

    public List<String> findAllUsingTypes() {
        return elementRepository.findAllGroupByType();
    }

    @ChainModification
    public ChainElement clone(String elementId, String parentId) {
        ChainElement copy = recursiveClone(findById(elementId));
        elementUtils.updateResetOnCopyProperties(copy);

        if (parentId != null) {
            ContainerChainElement parent = findById(parentId, ContainerChainElement.class);
            parent.addChildElement(copy);
            parent.setModifiedWhen(null);
            elementRepository.save(auditingHandler.markModified(parent));
        }

        logElementAction(copy, LogOperation.COPY);

        return copy;
    }

    @ChainModification
    private ChainElement recursiveClone(ChainElement root) {
        ChainElement savedCopy = elementRepository.save(root.copyWithoutSnapshot());
        if (root.getModifiedWhen().getTime() == root.getCreatedWhen().getTime()) {
            savedCopy.setCreatedWhen(null);
            savedCopy.setModifiedWhen(null);
        } else {
            savedCopy.setModifiedWhen(Timestamp.valueOf(LocalDateTime.now()));
        }

        if (root instanceof ContainerChainElement) {
            ContainerChainElement container = (ContainerChainElement) savedCopy;
            for (ChainElement element : elementRepository.findAllByParentId(root.getId())) {
                container.addChildElement(recursiveClone(element));
            }
        }

        return savedCopy;
    }

    @ChainModification
    public ChainDiff create(String chainId, CreateElementRequest createElementRequest) {
        final ChainDiff chainDiff = new ChainDiff();

        String parentElementId = createElementRequest.getParentElementId();
        String elementType = createElementRequest.getType();
        if (SwimlaneService.SWIMLANE_TYPE_NAME.equals(elementType)) {
            return swimlaneService.create(chainId);
        } else if (parentElementId != null) {
            ChainElement foundParent = elementRepository.findByIdAndChainId(parentElementId, chainId);
            if (!(foundParent instanceof ContainerChainElement)) {
                throw new ElementCreationException("Element " + parentElementId + " does not exist in chain " + chainId);
            }
            chainDiff.addCreatedElement(create(elementType, (ContainerChainElement) foundParent));
            chainDiff.addUpdatedElement(foundParent);
        } else {
            chainDiff.addCreatedElement(create(chainDiff, chainId, createElementRequest));
        }
        return chainDiff;
    }

    protected ChainElement create(final ChainDiff chainDiff, String chainId, CreateElementRequest createElementRequest) {
        String elementType = createElementRequest.getType();
        String swimlaneId = createElementRequest.getSwimlaneId();
        Chain chain = chainFinderService.findById(chainId);
        checkElementParentRestriction(elementType, null);
        ElementDescriptor descriptor = libraryService.getElementDescriptor(elementType);

        SwimlaneChainElement swimlane = findDefaultSwimlaneWithLockingByChainId(chainId)
                .orElse(null);
        if (swimlane != null) {
            if (descriptor.getType() == ElementType.REUSE) {
                swimlane = findReuseSwimlaneWithLockingByChainId(chainId)
                        .orElseGet(() -> {
                            SwimlaneChainElement reuseGroup = swimlaneService.createReuseSwimlane(chain);
                            chainDiff.setCreatedReuseSwimlaneId(reuseGroup.getId());
                            chainDiff.addCreatedElement(reuseGroup);
                            return reuseGroup;
                        });
            } else if (swimlaneId != null) {
                swimlane = elementRepository.findSwimlaneWithLockingByIdAndChainId(swimlaneId, chainId)
                        .orElseThrow(() -> new ElementCreationException(
                                "Swimlane " + swimlaneId + " does not exist in chain " + chainId));
                boolean rootParentReuse = Optional.ofNullable(createElementRequest.getParentElementId())
                        .flatMap(parentId -> chain.getElements().stream()
                                .filter(element -> StringUtils.equals(parentId, element.getId()))
                                .findFirst())
                        .map(this::findRootParent)
                        .map(libraryService::getElementDescriptor)
                        .map(elementDescriptor -> ElementType.REUSE == elementDescriptor.getType())
                        .orElse(false);
                if (swimlane.isReuseSwimlane() && !rootParentReuse) {
                    throw new ElementCreationException("Only Reuse element can be added to Reuse Swimlane");
                }
            }
        }

        ChainElement newElement = create(elementType, swimlane, chain);

        logElementAction(newElement, LogOperation.CREATE);
        return newElement;
    }

    @ChainModification
    protected ChainElement create(String elementType, @NonNull ContainerChainElement parentElement) {
        checkIfAllowedInContainers(elementType);
        checkElementParentRestriction(elementType, parentElement.getType());
        checkAddingChildParentRestriction(elementType, parentElement);

        Chain chain = parentElement.getChain();
        ChainElement element = create(elementType, parentElement.getSwimlane(), chain);
        parentElement.addChildElement(element);

        if (orderedElementService.isOrdered(element)) {
            orderedElementService.calculatePriority(parentElement, element);
        }

        auditingHandler.markModified(parentElement);
        element.setCreatedWhen(null);
        elementRepository.save(parentElement);

        logElementAction(element, LogOperation.CREATE);

        return element;
    }

    @ChainModification
    protected ChainElement create(String elementType, SwimlaneChainElement swimlane, Chain chain) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(elementType);
        ChainElement element;
        if (descriptor.isContainer()) {
            element = new ContainerChainElement();
        } else {
            element = new ChainElement();
        }
        element.setName(descriptor.getTitle());
        element.setType(elementType);
        element.setChain(chain);
        element.setSwimlane(swimlane);
        element.setProperties(createPropertiesMap(descriptor.getProperties(), element.getId(), chain.getId()));
        if (element instanceof ContainerChainElement) {
            element = elementRepository.save(element);
            ContainerChainElement container = (ContainerChainElement) element;
            for (Map.Entry<String, Quantity> childDefinition : descriptor.getAllowedChildren().entrySet()) {
                String libraryElement = childDefinition.getKey();
                Quantity libraryElementQuantity = childDefinition.getValue();

                ElementDescriptor childTypeDefinition = libraryService.getElementDescriptor(libraryElement);
                if (childTypeDefinition.isDeprecated() && !descriptor.isDeprecated()) {
                    continue;
                }

                int elementNumber;
                if (libraryElementQuantity == Quantity.TWO_OR_MANY) {
                    elementNumber = 2;
                } else {
                    elementNumber = 1;
                }

                for (int i = 0; i < elementNumber; i++) {
                    create(libraryElement, container);
                }
            }
        }

        element = elementRepository.save(element);
        return element;
    }

    protected ChainElement findRootParent(@NonNull ChainElement element) {
        ChainElement parentElement = element;
        while (parentElement.getParent() != null) {
            parentElement = parentElement.getParent();
        }
        return parentElement;
    }

    protected void checkElementParentRestriction(String elementType, String parentElementType) {
        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(elementType);
        if (elementDescriptor == null) {
            throw new ElementValidationException("Element of type " + elementType + " cannot be a child");
        }

        if (elementDescriptor.getParentRestriction() == null || elementDescriptor.getParentRestriction().isEmpty()) {
            return;
        }

        if (StringUtils.isBlank(parentElementType) || elementDescriptor.getParentRestriction().stream()
                .noneMatch(parentElType -> parentElType.equals(parentElementType))) {
            throw new ElementValidationException("Element " + elementType + " should be only inside parent element: "
                                                 + StringUtils.join(elementDescriptor.getParentRestriction(), ", "));
        }
    }

    protected void checkAddingChildParentRestriction(String childElementType, ContainerChainElement parent) {
        ElementDescriptor parentDescriptor = libraryService.getElementDescriptor(parent.getType());
        if (parentDescriptor != null) {
            Map<String, Quantity> childrenMap = parentDescriptor.getAllowedChildren();

            ElementDescriptor childElementDescriptor = libraryService.getElementDescriptor(childElementType);
            if (MapUtils.getObject(childrenMap, childElementType) == null && !childElementDescriptor.isInputEnabled()) {
                throw new ElementValidationException("Element with disabled input cannot be inside a parent element "
                                                     + parent.getType());
            }
            if (MapUtils.isEmpty(childrenMap)) {
                return;
            }

            Quantity elementCount = childrenMap.get(childElementType);
            if (elementCount == null) {
                throw new ElementValidationException("Element "
                                                     + childElementType + " is not allowed to be inside parent element " + parent.getType());
            }

            long childCount = parent.getElements().stream().filter(child -> child.getType().equals(childElementType)).count();
            if (!elementCount.test(((int) childCount) + 1)) {
                throw new ElementValidationException("Number of "
                                                     + childElementType + " elements inside parent " + parent.getType() + " element exceed limit");
            }
        }
    }

    protected void checkIfAllowedInContainers(String elementType) {
        Optional.ofNullable(libraryService.getElementDescriptor(elementType))
                .filter(descriptor -> !descriptor.isAllowedInContainers())
                .ifPresent(descriptor -> {
                    throw new ElementValidationException(
                            "The " + descriptor.getName() + " element cannot be inside a container");
                });
    }

    @ChainModification
    protected ContainerChainElement deleteElementFromParent(ChainElement child, boolean isImportProcess) {
        ContainerChainElement parent = child.getParent();
        if (parent == null) {
            return null;
        }

        parent.setModifiedWhen(null);
        parent = elementRepository.save(auditingHandler.markModified(parent));

        if (!isImportProcess) {
            ElementDescriptor parentDescriptor = libraryService.getElementDescriptor(parent.getType());
            if (parentDescriptor != null) {
                Map<String, Quantity> childrenMap = parentDescriptor.getAllowedChildren();
                if (MapUtils.isNotEmpty(childrenMap) && childrenMap.get(child.getType()) != null) {
                    Quantity elementCount = childrenMap.get(child.getType());

                    long childCount = parent.getElements().stream().filter(c -> c.getType().equals(child.getType())).count();

                    if ((elementCount == Quantity.ONE && childCount == 1)
                        || (elementCount == Quantity.ONE_OR_MANY && childCount == 1)
                        || (elementCount == Quantity.TWO_OR_MANY && childCount == 2)) {
                        throw new ElementValidationException("Number of " + child.getType()
                                                             + " elements inside parent " + parent.getType() + " element can't be lowered");
                    }
                }
            }
            parent.getElements().remove(child);
        }
        return parent;
    }

    protected Map<String, Object> createPropertiesMap(ElementProperties properties, String elementId, String chainId) {
        return new HashMap<>(properties.getAll().stream()
                .filter(prop -> StringUtils.isNotBlank(prop.getDefaultValue()))
                .collect(Collectors.toMap(
                        ElementProperty::getName,
                        prop -> PropertyValueType.STRING.equals(prop.getType())
                                ? ElementUtils.replaceDefaultValuePlaceholders(prop.getDefaultValue(), elementId, chainId)
                                : prop.defaultValue()
                )));
    }

    @ChainModification
    public ChainElement changeParent(ChainElement element, String newParentId) {
        checkIfAllowedInContainers(element.getType());

        ContainerChainElement parent = newParentId != null ? findById(newParentId, ContainerChainElement.class) : null;
        ContainerChainElement oldParent = element.getParent();
        auditingHandler.markModified(oldParent);

        oldParent.removeChildElement(element);
        if (parent != null) {
            parent.addChildElement(element);
            auditingHandler.markModified(parent);
        }
        return element;
    }

    @ChainModification
    public ChainElement save(ChainElement element) {
        auditingHandler.markModified(element);
        return elementRepository.save(element);
    }

    @ChainModification
    public List<ChainElement> saveAll(List<ChainElement> elements) {
        elements.forEach(auditingHandler::markModified);
        return elementRepository.saveAll(elements);
    }

    public void deleteAll(Collection<ChainElement> elements) {
        elementRepository.deleteAll(elements);
    }

    @ChainModification
    public ChainDiff updateRelativeProperties(ChainElement element, Map<String, Object> newProperties) {
        final ChainDiff chainDiff = new ChainDiff();

        if (orderedElementService.isOrdered(element)) {
            orderedElementService.extractPriorityNumber(element.getType(), newProperties)
                    .ifPresent(newPriorityNumber -> {
                        ChainDiff newChainDiff = orderedElementService
                                .changePriority(element.getParent(), element, newPriorityNumber);
                        chainDiff.merge(newChainDiff);
                    });
        }

        saveAll(chainDiff.getUpdatedElements());

        return chainDiff;
    }

    @ChainModification
    public ChainDiff deleteAllByIdsAndUpdateUnsaved(List<String> ids) {
        ChainDiff chainDiff = new ChainDiff();
        for (String id : ids) {
            chainDiff.merge(deleteByIdAndUpdateUnsaved(id));
        }
        return chainDiff;
    }

    @ChainModification
    public ChainDiff deleteByIdAndUpdateUnsaved(String id) {
        return deleteById(id, false);
    }

    private ChainDiff deleteById(String id, boolean isImportProcess) {
        final ChainDiff chainDiff = new ChainDiff();

        Optional<ChainElement> elementOptional = elementRepository.findById(id);
        if (elementOptional.isPresent()) {
            ChainElement element = elementOptional.get();
            if (element instanceof SwimlaneChainElement) {
                return swimlaneService.delete(id);
            }
            List<ChainElement> elements = new ArrayList<>();
            elements.add(element);
            if (orderedElementService.isOrdered(element)) {
                ChainDiff orderedChainDiff = orderedElementService.removeOrderedElement(element.getParent(), element);
                saveAll(orderedChainDiff.getUpdatedElements());
                chainDiff.merge(orderedChainDiff);
            }
            if (element instanceof ContainerChainElement containerElement) {
                collectAllNestedElements(elements, containerElement);
            }
            ChainElement parentElement = deleteElementFromParent(element, isImportProcess);
            if (parentElement != null) {
                chainDiff.addUpdatedElement(parentElement);
            }
            chainDiff.addRemovedElements(elements);
            Optional.ofNullable(libraryService.getElementDescriptor(element))
                    .filter(ElementDescriptor::isReferencedByAnotherElement)
                    .ifPresent(descriptor -> deleteElementReferences(chainDiff, element));

            for (ChainElement elementToRemove : elements) {
                chainDiff.addRemovedDependencies(elementToRemove.getInputDependencies());
                chainDiff.addRemovedDependencies(elementToRemove.getOutputDependencies());
            }

            elementRepository.deleteAll(elements);

            logElementsAction(elements, LogOperation.DELETE);
        }
        return chainDiff;
    }

    private void collectAllNestedElements(List<ChainElement> elements, ContainerChainElement container) {
        elements.addAll(container.getElements());
        for (ChainElement element : container.getElements()) {
            if (element instanceof ContainerChainElement) {
                collectAllNestedElements(elements, (ContainerChainElement) element);
            }
        }
    }

    private void deleteElementReferences(ChainDiff chainDiff, ChainElement referencedElement) {
        String chainId = Optional.ofNullable(referencedElement.getChain())
                .map(Chain::getId)
                .orElse(null);
        Map<String, ElementDescriptor> elementDescriptors = libraryService.getElementsWithReferenceProperties();
        List<ChainElement> elements = elementRepository.findAllByChainIdAndTypeIn(chainId, elementDescriptors.keySet());

        List<ChainElement> elementsToUpdate = new ArrayList<>();
        for (ChainElement element : elements) {
            ElementDescriptor elementDescriptor = elementDescriptors.get(element.getType());
            List<ElementProperty> referenceProperties = elementDescriptor.getReferenceProperties();

            boolean elementUpdated = false;
            for (ElementProperty referenceProperty : referenceProperties) {
                String propertyValue = element.getPropertyAsString(referenceProperty.getName());
                if (StringUtils.equals(propertyValue, referencedElement.getId())) {
                    element.getProperties().remove(referenceProperty.getName());
                    elementUpdated = true;
                }
            }
            if (elementUpdated) {
                elementsToUpdate.add(element);
            }
        }

        if (!elementsToUpdate.isEmpty()) {
            chainDiff.addUpdatedElements(saveAll(elementsToUpdate));
        }
    }

    @ChainModification
    public ChainElement group(String chainId, List<String> elementsId) throws IllegalArgumentException {
        Chain chain = chainFinderService.findById(chainId);

        ContainerChainElement group = ContainerChainElement.builder()
                .name(CONTAINER_DEFAULT_NAME)
                .type(CONTAINER_TYPE_NAME)
                .chain(chain)
                .elements(new ArrayList<>())
                .properties(new HashMap<>())
                .build();

        List<ChainElement> elements = elementRepository.findAllById(elementsId);
        if (!elements.isEmpty()) {
            long elementsWithParent = elements.stream()
                    .filter(it -> it.getParent() != null)
                    .count();
            if (elementsWithParent > 0) {
                throw new ElementValidationException("Elements with non-null parent cannot be grouped");
            }
            elements.forEach(element -> checkIfAllowedInContainers(element.getType()));

            group.addChildrenElements(elements);
            group.setSwimlane(elements.get(0).getSwimlane());
        }

        group = elementRepository.saveEntity(group);

        logElementAction(group, LogOperation.CREATE);
        logElementsAction(elements, LogOperation.GROUP);

        return group;
    }

    @ChainModification
    public List<ChainElement> ungroup(String groupId) {
        ContainerChainElement group = findById(groupId, ContainerChainElement.class);
        if (StringUtils.equals(group.getType(), CONTAINER_TYPE_NAME)) {
            List<ChainElement> children = group.getElements();
            children.forEach(child -> child.setParent(null));
            children = elementRepository.saveAll(children);

            elementRepository.delete(group);

            logElementAction(group, LogOperation.DELETE);
            logElementsAction(children, LogOperation.UNGROUP);

            return children;
        } else {
            throw new ElementValidationException("Element with id = " + groupId + " is not a group");
        }
    }

    private void logElementsAction(List<ChainElement> elements, LogOperation operation) {
        for (ChainElement element : elements) {
            logElementAction(element, operation);
        }
    }

    private void logElementAction(ChainElement element, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.ELEMENT)
                .entityId(element.getId())
                .entityName(element.getName())
                .parentType(element.getChain() == null ? null : EntityType.CHAIN)
                .parentId(element.getChain() == null ? null : element.getChain().getId())
                .parentName(element.getChain() == null ? null : element.getChain().getName())
                .operation(operation)
                .build());
    }

    public List<UsedSystem> getUsedSystemIdsByChainIds(List<String> chainIds) {
        List<UsedSystem> usedSystems = new ArrayList<>();
        List<String> elementTypes = new ArrayList<>();

        for (var element : ElementsWithSystemUsage.values()) {
            elementTypes.add(element.getElementName());
        }

        List<ChainElement> elements = elementRepository.findAllByTypeInAndChainNotNull(elementTypes);

        for (String chainId : chainIds) {
            for (ChainElement chainElement : elements) {
                if (chainElement.getChain() != null) {
                    if (Objects.equals(chainElement.getChain().getId(), chainId)) {
                        String systemId = (String) chainElement.getProperties().get(SYSTEM_ID);
                        String specificationId = (String) chainElement.getProperties().get(SPECIFICATION_ID);

                        if (!StringUtils.isBlank(systemId)) {
                            if (usedSystems.stream().noneMatch(system -> system.getSystemId().equals(systemId))) {
                                UsedSystem usedSystem = new UsedSystem(systemId, new ArrayList<>());
                                usedSystems.add(usedSystem);
                            }

                            if (!StringUtils.isBlank(specificationId)) {
                                UsedSystem usedSystem = usedSystems.stream().filter(system -> system.getSystemId().equals(systemId)).findFirst().orElse(null);

                                if (usedSystem != null && !usedSystem.getUsedSystemModelIds().contains(specificationId)) {
                                    usedSystem.getUsedSystemModelIds().add(specificationId);
                                }
                            }
                        }
                    }
                }
            }
        }

        return usedSystems;
    }

    public List<UsedSystem> getAllUsedSystemIds() {
        List<String> elementTypes = new ArrayList<>();

        for (var element : ElementsWithSystemUsage.values()) {
            elementTypes.add(element.getElementName());
        }

        List<ChainElement> elements = elementRepository.findAllByTypeInAndChainNotNull(elementTypes);

        return getUsedSystemsFromElements(elements);
    }

    public boolean isElementDeprecated(ChainElement chainElement) {
        return Optional.ofNullable(libraryService.getElementDescriptor(chainElement))
                .map(ElementDescriptor::isDeprecated)
                .orElse(false);
    }

    public boolean isElementUnsupported(ChainElement chainElement) {
        return Optional.ofNullable(libraryService.getElementDescriptor(chainElement))
                .map(ElementDescriptor::isUnsupported)
                .orElse(false);
    }

    public void validateElementProperties(ChainElement element) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(element);
        if (descriptor == null) {
            return;
        }

        for (ElementProperty property : descriptor.getProperties().getAll()) {
            if (!elementUtils.isMandatoryPropertyPresent(property, element)) {
                throw new ElementValidationException("Value not found for " + property.getName());
            }

            if (property.getMask() != null) {
                String propertyValue = element.getPropertyAsString(property.getName());
                if (propertyValue == null) {
                    throw new ElementValidationException("Invalid Value for " + property.getName());
                }

                Matcher matcher = property.getMask().matcher(propertyValue);
                if (!matcher.find()) {
                    throw new ElementValidationException("Invalid Value for " + property.getName());
                }
            }
        }
        for (CustomTab customTab : descriptor.getCustomTabs()) {
            if (customTab.getValidation() != null && !customTab.getValidation().arePropertiesValid(element.getProperties())) {
                throw new ElementValidationException("Some mandatory properties are missing on tab " + customTab.getName());
            }
        }
    }

    private List<UsedSystem> getUsedSystemsFromElements(List<ChainElement> elements) {
        List<UsedSystem> usedSystems = new ArrayList<>();
        for (ChainElement chainElement : elements) {
            if (chainElement.getChain() != null) {
                String systemId = (String) chainElement.getProperties().get(SYSTEM_ID);
                String specificationId = (String) chainElement.getProperties().get(SPECIFICATION_ID);

                if (!StringUtils.isBlank(systemId)) {
                    if (usedSystems.stream().noneMatch(system -> system.getSystemId().equals(systemId))) {
                        UsedSystem usedSystem = new UsedSystem(systemId, new ArrayList<>());
                        usedSystems.add(usedSystem);
                    }

                    if (!StringUtils.isBlank(specificationId)) {
                        UsedSystem usedSystem = usedSystems.stream().filter(system -> system.getSystemId().equals(systemId)).findFirst().orElse(null);

                        if (usedSystem != null && !usedSystem.getUsedSystemModelIds().contains(specificationId)) {
                            usedSystem.getUsedSystemModelIds().add(specificationId);
                        }
                    }
                }
            }
        }
        return usedSystems;
    }

    private List<ChainElement> getAllChildElements(List<ChainElement> chainElementList) {
        return chainElementList
                .stream()
                .flatMap(chainElement -> {
                    if (chainElement instanceof ContainerChainElement containerChainElement) {
                        return getAllChildElements(containerChainElement.getElements()).stream();
                    }
                    if (chainElement instanceof SwimlaneChainElement swimlaneChainElement) {
                        return getAllChildElements(swimlaneChainElement.getElements()).stream();
                    }
                    return Stream.of(chainElement);
                })
                .collect(Collectors.toList());
    }

    private List<ChainElement> getAllParentElements(List<ChainElement> chainElementList) {
        return chainElementList
                .stream()
                .filter(chainElement -> (chainElement instanceof ContainerChainElement) || (chainElement instanceof SwimlaneChainElement))
                .collect(Collectors.toList());
    }

    public Map<String, String> provideNavigationPath(String chainId) {
        Chain chain = chainFinderService.findById(chainId);
        return chain.getAncestors();
    }
}
