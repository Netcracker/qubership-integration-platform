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

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.configuration.aspect.ChainModification;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementDeletionException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementTransferException;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

@Service
public class SwimlaneService {

    public static final String SWIMLANE_TYPE_NAME = "swimlane";
    public static final String COLOR_PROPERTY_NAME = "color";
    public static final String DEFAULT_SWIMLANE_NAME = "Default swimlane";
    public static final String REUSE_SWIMLANE_NAME = "Reuse swimlane";
    public static final String REUSE_SWIMLANE_COLOR = "Green";

    private final LibraryElementsService libraryService;
    private final ElementService elementService;
    private final ChainService chainService;
    private final ChainFinderService chainFinderService;

    @Autowired
    public SwimlaneService(
            LibraryElementsService libraryService,
            @Lazy ElementService elementService,
            @Lazy ChainService chainService,
            ChainFinderService chainFinderService
    ) {
        this.libraryService = libraryService;
        this.elementService = elementService;
        this.chainService = chainService;
        this.chainFinderService = chainFinderService;
    }

    @Transactional
    @ChainModification
    public ChainDiff create(String chainId) {
        final ChainDiff chainDiff = new ChainDiff();

        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(SWIMLANE_TYPE_NAME);
        Chain chain = chainFinderService.findById(chainId);
        if (chain.getDefaultSwimlane() != null) {
            SwimlaneChainElement swimlaneElement = createSwimlaneElement(elementDescriptor.getTitle(), elementDescriptor, chain);
            chainDiff.addCreatedElement(elementService.save(swimlaneElement));
        } else {
            SwimlaneChainElement defaultSwimlane = createSwimlaneElement(DEFAULT_SWIMLANE_NAME, elementDescriptor, chain);
            defaultSwimlane = (SwimlaneChainElement) elementService.save(defaultSwimlane);
            chain.setDefaultSwimlane(defaultSwimlane);

            chainDiff.addUpdatedElements(updateSwimlaneForElements(
                    defaultSwimlane,
                    chain.getRootElements(),
                    descriptor -> descriptor == null || descriptor.getType() != ElementType.REUSE
            ));
            chainDiff.addCreatedElement(defaultSwimlane);
            chainDiff.setCreatedDefaultSwimlaneId(defaultSwimlane.getId());

            boolean chainHasReuseElements = chain.getElements().stream()
                    .map(libraryService::getElementDescriptor)
                    .anyMatch(descriptor -> ElementType.REUSE == descriptor.getType());
            if (chainHasReuseElements) {
                SwimlaneChainElement reuseSwimlane = createSwimlaneElement(REUSE_SWIMLANE_NAME, elementDescriptor, chain);
                reuseSwimlane.getProperties().put(COLOR_PROPERTY_NAME, REUSE_SWIMLANE_COLOR);
                reuseSwimlane = (SwimlaneChainElement) elementService.save(reuseSwimlane);
                chain.setReuseSwimlane(reuseSwimlane);

                chainDiff.addUpdatedElements(updateSwimlaneForElements(
                        reuseSwimlane,
                        chain.getRootElements(),
                        descriptor -> descriptor != null && descriptor.getType() == ElementType.REUSE
                ));
                chainDiff.addCreatedElement(reuseSwimlane);
                chainDiff.setCreatedReuseSwimlaneId(reuseSwimlane.getId());
            }
            chainService.save(chain);
        }
        return chainDiff;
    }

    @Transactional
    @ChainModification
    public SwimlaneChainElement createReuseSwimlane(Chain chain) {
        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(SWIMLANE_TYPE_NAME);
        SwimlaneChainElement reuseSwimlane = createSwimlaneElement(REUSE_SWIMLANE_NAME, elementDescriptor, chain);
        reuseSwimlane.getProperties().put(COLOR_PROPERTY_NAME, REUSE_SWIMLANE_COLOR);

        reuseSwimlane = (SwimlaneChainElement) elementService.save(reuseSwimlane);
        chain.setReuseSwimlane(reuseSwimlane);
        chainService.save(chain);
        return reuseSwimlane;
    }

    @Transactional
    @ChainModification
    public ChainDiff transferElementsToSwimlane(String chainId, String swimlaneId, List<ChainElement> elements) {
        Chain chain = chainFinderService.findById(chainId);
        final ChainDiff chainDiff = new ChainDiff();
        for (ChainElement element : elements) {
            chainDiff.merge(transferElementToSwimlane(chain, swimlaneId, element));
        }
        return chainDiff;
    }

    @Transactional
    @ChainModification
    public ChainDiff transferElementToSwimlane(Chain chain, String swimlaneId, ChainElement element) {
        final ChainDiff chainDiff = new ChainDiff();

        SwimlaneChainElement swimlane = (SwimlaneChainElement) chain.getElements().stream()
                .filter(chainElement -> chainElement instanceof SwimlaneChainElement)
                .filter(swimlaneElement -> StringUtils.equals(swimlaneElement.getId(), swimlaneId))
                .findFirst()
                .orElse(null);
        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(element);
        if (ElementType.REUSE != elementDescriptor.getType()) {
            if (swimlane == null) {
                updateElementsHierarchy(
                        Collections.singletonList(element),
                        chainElement -> chainElement.setSwimlane(chain.getDefaultSwimlane())
                );
            } else {
                boolean parentElementReuse = Optional.ofNullable(elementService.findRootParent(element))
                        .map(libraryService::getElementDescriptor)
                        .map(descriptor -> ElementType.REUSE == descriptor.getType())
                        .orElse(false);
                if (Objects.equals(swimlane, chain.getReuseSwimlane()) && !parentElementReuse) {
                    throw new ElementTransferException("Element " + element.getId() + " cannot be moved to Reuse group");
                }
                updateElementsHierarchy(Collections.singletonList(element), chainElement -> chainElement.setSwimlane(swimlane));
            }
        } else {
            SwimlaneChainElement reuseSwimlane = Optional.ofNullable(chain.getReuseSwimlane())
                    .orElseGet(() -> {
                        SwimlaneChainElement swimlaneElement = null;
                        if (chain.getDefaultSwimlane() != null) {
                            swimlaneElement = createReuseSwimlane(chain);
                            chainDiff.setCreatedReuseSwimlaneId(swimlaneElement.getId());
                            chainDiff.addCreatedElement(swimlaneElement);
                        }
                        return swimlaneElement;
                    });
            updateElementsHierarchy(Collections.singletonList(element), chainElement -> chainElement.setSwimlane(reuseSwimlane));
            element.setSwimlane(reuseSwimlane);
        }
        elementService.save(element);

        chainDiff.addUpdatedElement(element);
        return chainDiff;
    }

    @Transactional
    @ChainModification
    public ChainDiff delete(String swimlaneId) {
        final ChainDiff chainDiff = new ChainDiff();

        Optional<SwimlaneChainElement> swimlaneOptional = elementService.findSwimlaneWithLockingById(swimlaneId);
        if (swimlaneOptional.isEmpty()) {
            return chainDiff;
        }

        SwimlaneChainElement swimlaneElement = swimlaneOptional.get();
        Chain chain = swimlaneElement.getChain();
        if (swimlaneElement.isDefaultSwimlane()) {
            deleteDefaultSwimlane(chainDiff, chain);
        } else if (swimlaneElement.isReuseSwimlane()) {
            if (CollectionUtils.isEmpty(swimlaneElement.getElements())) {
                chain.removeElement(swimlaneElement);
                chain.setReuseSwimlane(null);

                elementService.delete(swimlaneElement);
                chainDiff.addRemovedElement(swimlaneElement);
            } else {
                deleteDefaultSwimlane(chainDiff, chain);
            }
        } else {
            SwimlaneChainElement defaultSwimlane = chain.getDefaultSwimlane();
            if (defaultSwimlane != null) {
                chainDiff.addUpdatedElements(updateSwimlaneForElements(
                        defaultSwimlane,
                        swimlaneElement.getRootElements(),
                        descriptor -> true
                ));
            } else {
                chainDiff.addUpdatedElements(removeElementsFromSwimlane(swimlaneElement, swimlaneElement.getRootElements()));
            }
            chain.removeElement(swimlaneElement);

            elementService.delete(swimlaneElement);
            chainDiff.addRemovedElement(swimlaneElement);
        }
        chainService.save(chain);
        return chainDiff;
    }

    private SwimlaneChainElement createSwimlaneElement(String name, ElementDescriptor elementDescriptor, Chain chain) {
        SwimlaneChainElement swimlaneElement = new SwimlaneChainElement();
        swimlaneElement.setName(name);
        swimlaneElement.setType(elementDescriptor.getName());
        swimlaneElement.setChain(chain);
        swimlaneElement.setProperties(
                elementService.createPropertiesMap(elementDescriptor.getProperties(), swimlaneElement.getId(), chain.getId())
        );
        swimlaneElement.setCreatedWhen(null);
        return swimlaneElement;
    }

    private List<ChainElement> updateSwimlaneForElements(
            SwimlaneChainElement groupElement,
            List<ChainElement> chainElements,
            Predicate<ElementDescriptor> elementFilter
    ) {
        List<ChainElement> updatedElements = chainElements.stream()
                .filter(element -> !SWIMLANE_TYPE_NAME.equals(element.getType()))
                .filter(element -> elementFilter.test(libraryService.getElementDescriptor(element)))
                .toList();
        updateElementsHierarchy(updatedElements, groupElement::addElement);
        return updatedElements;
    }

    private List<ChainElement> removeElementsFromSwimlane(SwimlaneChainElement groupElement, List<ChainElement> elements) {
        updateElementsHierarchy(elements, groupElement::removeElement);
        return elements;
    }

    private void updateElementsHierarchy(List<ChainElement> elements, Consumer<ChainElement> action) {
        elements.stream()
                .peek(action)
                .forEach(element -> {
                    if (element instanceof ContainerChainElement) {
                        updateElementsHierarchy(((ContainerChainElement) element).getElements(), action);
                    }
                });
    }

    private void deleteDefaultSwimlane(ChainDiff chainDiff, Chain chain) {
        long commonSwimlanesCount = chain.getElements().stream()
                .filter(element -> element instanceof SwimlaneChainElement)
                .filter(swimlane -> !Objects.equals(swimlane, chain.getDefaultSwimlane())
                        && !Objects.equals(swimlane, chain.getReuseSwimlane()))
                .count();
        if (commonSwimlanesCount > 0) {
            throw new ElementDeletionException(
                    "Default and Reuse swimlanes cannot be removed if the chain contains other swimlanes");
        }

        SwimlaneChainElement defaultSwimlane = chain.getDefaultSwimlane();
        chainDiff.addUpdatedElements(removeElementsFromSwimlane(defaultSwimlane, defaultSwimlane.getRootElements()));
        chain.removeElement(defaultSwimlane);
        chain.setDefaultSwimlane(null);

        elementService.delete(defaultSwimlane);
        chainDiff.addRemovedElement(defaultSwimlane);

        elementService.findReuseSwimlaneWithLockingByChainId(chain.getId())
                .ifPresent(reuseSwimlane -> {
                    chainDiff.addUpdatedElements(removeElementsFromSwimlane(reuseSwimlane, reuseSwimlane.getRootElements()));
                    chain.removeElement(reuseSwimlane);
                    chain.setReuseSwimlane(null);

                    elementService.delete(reuseSwimlane);
                    chainDiff.addRemovedElement(reuseSwimlane);
                });
    }
}
