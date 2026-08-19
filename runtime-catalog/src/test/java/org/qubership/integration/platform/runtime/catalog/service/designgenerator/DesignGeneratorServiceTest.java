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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramMode;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.ElementsSequenceDiagram;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.service.DependencyService;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.interfaces.DesignProcessor;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two diagram entry points of {@link DesignGeneratorService} for a chain with no elements:
 * each requested mode yields a diagram carrying the chain and snapshot ids, the chain name is looked
 * up for the participant line, and the builder still emits a source per diagram language.
 */
@ExtendWith(MockitoExtension.class)
class DesignGeneratorServiceTest {

    @Mock
    private ElementService elementService;
    @Mock
    private DependencyService dependencyService;
    @Mock
    private LibraryElementsService libraryService;
    @Mock
    private ChainFinderService chainFinderService;

    private DesignGeneratorService service() {
        return new DesignGeneratorService(
                elementService, dependencyService, libraryService, chainFinderService, List.<DesignProcessor>of());
    }

    @Test
    void generateChainSequenceDiagramProducesADiagramPerRequestedMode() {
        when(elementService.findAllByChainId("chain-1")).thenReturn(List.of());
        when(dependencyService.findAllByElementsIDs(any())).thenReturn(List.of());
        Chain chain = Chain.builder().build();
        chain.setId("chain-1");
        chain.setName("Orders");
        when(chainFinderService.findById("chain-1")).thenReturn(chain);

        Map<DiagramMode, ElementsSequenceDiagram> result =
                service().generateChainSequenceDiagram("chain-1", List.of(DiagramMode.FULL));

        assertThat(result).containsOnlyKeys(DiagramMode.FULL);
        ElementsSequenceDiagram diagram = result.get(DiagramMode.FULL);
        assertThat(diagram.getChainId()).isEqualTo("chain-1");
        assertThat(diagram.getSnapshotId()).isNull();
        assertThat(diagram.getDiagramSources()).isNotEmpty();
        verify(chainFinderService).findById("chain-1");
    }

    @Test
    void generateSnapshotSequenceDiagramUsesSnapshotElementsAndCarriesTheSnapshotId() {
        when(elementService.findAllBySnapshotId("snap-1")).thenReturn(List.of());
        when(dependencyService.findAllByElementsIDs(any())).thenReturn(List.of());
        Chain chain = Chain.builder().build();
        chain.setId("chain-1");
        chain.setName("Orders");
        when(chainFinderService.findById("chain-1")).thenReturn(chain);

        Map<DiagramMode, ElementsSequenceDiagram> result =
                service().generateSnapshotSequenceDiagram("chain-1", "snap-1", List.of(DiagramMode.FULL));

        ElementsSequenceDiagram diagram = result.get(DiagramMode.FULL);
        assertThat(diagram.getChainId()).isEqualTo("chain-1");
        assertThat(diagram.getSnapshotId()).isEqualTo("snap-1");
        assertThat(diagram.getDiagramSources()).isNotEmpty();
    }
}
