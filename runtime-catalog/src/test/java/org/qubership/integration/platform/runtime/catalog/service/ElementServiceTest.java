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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.verification.properties.verifiers.MandatoryPropertyVerificationHelper;
import org.springframework.data.auditing.AuditingHandler;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ElementServiceTest {

    @Mock
    private ElementRepository elementRepository;
    @Mock
    private AuditingHandler auditingHandler;
    @Mock
    private LibraryElementsService libraryService;
    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private SwimlaneService swimlaneService;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private OrderedElementService orderedElementService;
    @Mock
    private SystemEnvironmentsGenerator systemEnvironmentsGenerator;
    @Mock
    private SystemBaseService systemBaseService;
    @Mock
    private SystemModelBaseService systemModelBaseService;
    @Mock
    private MandatoryPropertyVerificationHelper mandatoryPropertyVerificationHelper;
    @Mock
    private PropertyPlaceholderService propertyPlaceholderService;

    @InjectMocks
    private ElementService elementService;

    @DisplayName("replaceImportedChainElements should persist nested swimlanes and containers before children")
    @Test
    void replaceImportedChainElementsShouldPersistNestedParentsBeforeChildren() {
        SwimlaneChainElement nestedSwimlane = SwimlaneChainElement.builder().id("nested-swimlane").build();
        ChainElement script = ChainElement.builder().id("script").type("script").build();
        nestedSwimlane.addElement(script);

        ContainerChainElement reuseContainer = ContainerChainElement.builder().id("reuse").type("reuse").build();
        reuseContainer.addChildElement(nestedSwimlane);

        SwimlaneChainElement rootSwimlane = SwimlaneChainElement.builder().id("root-swimlane").build();

        elementService.replaceImportedChainElements(List.of(reuseContainer, rootSwimlane));

        verify(elementRepository, times(2)).actualizeCollectionStateWOUpdates(any(), any());
        verify(elementRepository, times(2)).actualizeCollectionStateOnlyUpdates(any(), any());
    }

    @DisplayName("replaceImportedChainElements should no-op repository lists when roots are empty")
    @Test
    void replaceImportedChainElementsShouldNoOpWhenRootsEmpty() {
        elementService.replaceImportedChainElements(Collections.emptyList());

        verify(elementRepository, times(2)).actualizeCollectionStateWOUpdates(eq(Collections.emptyList()), eq(Collections.emptyList()));
        verify(elementRepository, times(2)).actualizeCollectionStateOnlyUpdates(eq(Collections.emptyList()), eq(Collections.emptyList()));
    }
}
