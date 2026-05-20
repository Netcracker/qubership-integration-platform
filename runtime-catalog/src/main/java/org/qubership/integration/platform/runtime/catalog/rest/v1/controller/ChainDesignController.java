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

package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramMode;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.ElementsSequenceDiagram;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.design.GenerateChainDesignRequest;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.DesignGeneratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(value = "/v1/design-generator/chains/{chainId}", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "chain-design-controller", description = "Chain Design Controller")
public class ChainDesignController {
    private final DesignGeneratorService designGeneratorService;

    @Autowired
    public ChainDesignController(DesignGeneratorService designGeneratorService) {
        this.designGeneratorService = designGeneratorService;
    }

    @Deprecated(since = "24.3")
    @GetMapping
    @Operation(description = "Generate chain sequence diagram")
    public ResponseEntity<ElementsSequenceDiagram> generateChainSequenceDiagram(
            @PathVariable @Parameter(description = "Chain id") String chainId
    ) {
        Map<DiagramMode, ElementsSequenceDiagram> elementsSequenceDiagrams =
                designGeneratorService.generateChainSequenceDiagram(chainId, List.of(DiagramMode.FULL));
        return ResponseEntity.ok(elementsSequenceDiagrams.get(DiagramMode.FULL));
    }

    @PostMapping
    @Operation(description = "Generate chain sequence diagrams")
    public ResponseEntity<Map<DiagramMode, ElementsSequenceDiagram>> generateChainSequenceDiagrams(
            @PathVariable @Parameter(description = "Chain id") String chainId,
            @RequestBody @Parameter(description = "Design generation request") GenerateChainDesignRequest request
    ) {
        return ResponseEntity.ok(designGeneratorService.generateChainSequenceDiagram(chainId, request.getDiagramModes()));
    }

    @Deprecated(since = "24.3")
    @GetMapping("/snapshots/{snapshotId}")
    @Operation(description = "Generate chain sequence diagram from specified snapshot")
    public ResponseEntity<ElementsSequenceDiagram> generateSnapshotSequenceDiagram(
            @PathVariable @Parameter(description = "Chain id") String chainId,
            @PathVariable @Parameter(description = "Snapshot id of specified chain") String snapshotId
    ) {
        Map<DiagramMode, ElementsSequenceDiagram> elementsSequenceDiagrams =
                designGeneratorService.generateSnapshotSequenceDiagram(chainId, snapshotId, List.of(DiagramMode.FULL));
        return ResponseEntity.ok(elementsSequenceDiagrams.get(DiagramMode.FULL));
    }

    @PostMapping("/snapshots/{snapshotId}")
    @Operation(description = "Generate chain sequence diagrams from specified snapshot")
    public ResponseEntity<Map<DiagramMode, ElementsSequenceDiagram>> generateSnapshotSequenceDiagrams(
            @PathVariable @Parameter(description = "Chain id") String chainId,
            @PathVariable @Parameter(description = "Snapshot id of specified chain") String snapshotId,
            @RequestBody @Parameter(description = "Design generation request") GenerateChainDesignRequest request
    ) {
        return ResponseEntity.ok(designGeneratorService.generateSnapshotSequenceDiagram(chainId, snapshotId, request.getDiagramModes()));
    }
}
