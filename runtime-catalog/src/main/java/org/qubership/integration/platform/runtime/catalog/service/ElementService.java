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
import org.qubership.integration.platform.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.catalog.service.ElementBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.catalog.model.constant.CamelNames.*;
import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.SYSTEM_ID;

@Slf4j
@Service
@Transactional
public class ElementService extends ElementBaseService {

    public static final String CONTAINER_TYPE_NAME = "container";
    private static final String GROUP_ID_PROPERTY = "groupId";
    public static final String SWIMLANE_TYPE_NAME = "swimlane";

    protected final EnvironmentService environmentService;
    protected final AuditingHandler auditingHandler;

    @Autowired
    public ElementService(
            ElementRepository elementRepository,
            AuditingHandler jpaAuditingHandler,
            EnvironmentService environmentService
    ) {
        super(elementRepository);
        this.auditingHandler = jpaAuditingHandler;
        this.environmentService = environmentService;
    }

    public ChainElement findById(String id) {
        return elementRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(CHAIN_ELEMENT_WITH_ID_NOT_FOUND_MESSAGE + id));
    }

    public Optional<ChainElement> findByIdOptional(String id) {
        return elementRepository.findById(id);
    }

    public List<ChainElement> findAllBySnapshotIdAndType(String snapshotId, String type) {
        return elementRepository.findAllBySnapshotIdAndType(snapshotId, type);
    }

    public <T extends ChainElement> T findById(String id, Class<T> elementClass) {
        ChainElement element = findById(id);
        if (elementClass.isAssignableFrom(element.getClass())) {
            return elementClass.cast(element);
        }
        return null;
    }

    public ChainElement save(ChainElement element) {
        auditingHandler.markModified(element);
        return elementRepository.save(element);
    }

    public void delete(ChainElement element) {
        elementRepository.delete(element);
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

        List<ServiceEnvironment> environments = environmentService.generateSystemEnvironments(elementsBySystemId.keySet());
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
                    systemId = element.getProperties() == null ? null :
                            (String) element.getProperties().get(SYSTEM_ID);
                    break;
                default:
                    continue;
            }
            if (StringUtils.isEmpty(systemId))
                continue;

            if (!elementsBySystemId.containsKey(systemId))
                elementsBySystemId.put(systemId, new ArrayList<>());
            elementsBySystemId.get(systemId).add(element);
        }
        return elementsBySystemId;
    }

    public void setActualizedChainElements(List<ChainElement> oldChainElementStates, List<ChainElement> newChainElementStates){
        //We must actualize states of non container elements before
        elementRepository.actualizeCollectionStateWOUpdates(getAllChildElements(oldChainElementStates),getAllChildElements(newChainElementStates));
        elementRepository.actualizeCollectionStateWOUpdates(getAllParentElements(oldChainElementStates),getAllParentElements(newChainElementStates));
        //Merge (updates) will persist child entities too, so we need to do it as a last step
        elementRepository.actualizeCollectionStateOnlyUpdates(getAllChildElements(oldChainElementStates),getAllChildElements(newChainElementStates));
        elementRepository.actualizeCollectionStateOnlyUpdates(getAllParentElements(oldChainElementStates),getAllParentElements(newChainElementStates));
    }

    private List<ChainElement> getAllChildElements(List<ChainElement> chainElementList){
        return chainElementList
                .stream()
                .flatMap(chainElement -> {
                    if (chainElement instanceof ContainerChainElement containerChainElement){
                        return getAllChildElements(containerChainElement.getElements()).stream();
                    }
                    if (chainElement instanceof SwimlaneChainElement swimlaneChainElement){
                        return getAllChildElements(swimlaneChainElement.getElements()).stream();
                    }
                    return Stream.of(chainElement);
                })
                .collect(Collectors.toList());
    }

    private List<ChainElement> getAllParentElements(List<ChainElement> chainElementList){
        return chainElementList
                .stream()
                .filter(chainElement -> (chainElement instanceof ContainerChainElement) || (chainElement instanceof SwimlaneChainElement))
                .collect(Collectors.toList());
    }
}
