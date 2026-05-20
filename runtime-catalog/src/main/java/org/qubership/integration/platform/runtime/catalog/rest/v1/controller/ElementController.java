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
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.model.chain.element.UsedProperty;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainDiffResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.*;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ChainDiffMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ElementMapper;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.TransferableElementService;
import org.qubership.integration.platform.runtime.catalog.service.UsedPropertiesAnalyzer;
import org.qubership.integration.platform.runtime.catalog.service.codeview.ElementsCodeviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/v1/chains/{chainId}/elements", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "element-controller", description = "Element Controller")
public class ElementController {

    private final TransferableElementService transferableElementService;
    private final ElementsCodeviewService elementsCodeviewService;
    private final UsedPropertiesAnalyzer usedPropertiesAnalyzer;
    private final ElementMapper elementMapper;
    private final ChainDiffMapper chainDiffMapper;
    private final ActionsLogService actionLogger;

    @Autowired
    public ElementController(ElementsCodeviewService elementsCodeviewService,
                             ElementMapper elementMapper,
                             ChainDiffMapper chainDiffMapper,
                             ActionsLogService actionLogger,
                             TransferableElementService transferableElementService,
                             UsedPropertiesAnalyzer usedPropertiesAnalyzer) {
        this.elementsCodeviewService = elementsCodeviewService;
        this.elementMapper = elementMapper;
        this.chainDiffMapper = chainDiffMapper;
        this.actionLogger = actionLogger;
        this.transferableElementService = transferableElementService;
        this.usedPropertiesAnalyzer = usedPropertiesAnalyzer;
    }

    @GetMapping("/{elementId}")
    @Operation(description = "Get specific element from the chain")
    public ResponseEntity<ElementResponse> findElementById(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                           @PathVariable @Parameter(description = "Element id") String elementId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find element with id: {}", elementId);
        }
        ChainElement element = transferableElementService.findById(elementId);
        ElementResponse response = elementMapper.toElementResponse(element);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(description = "Get all elements from the chain")
    public ResponseEntity<List<ElementResponse>> getElementsByChainId(@PathVariable @Parameter(description = "Chain id") String chainId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find all elements in chain with id: {}", chainId);
        }
        List<ChainElement> elements = transferableElementService.findAllByChainId(chainId);
        List<ElementResponse> responses = elementMapper.toElementResponses(elements);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/type/{type}")
    @Operation(description = "Get all elements of specific type")
    public ResponseEntity<List<ElementWithChainNameResponse>> getElementsWithChainNameByType(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                                                             @PathVariable @Parameter(description = "Inner element type") String type) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find all elements with type: {}", type);
        }
        List<Pair<String, ChainElement>> elements = transferableElementService.findAllElementsWithChainNameByElementType(type);
        List<ElementWithChainNameResponse> response = elementMapper.toElementWithChainNameResponses(elements);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/code")
    @Operation(description = "Get all elements from the chain in code representation for end-user")
    public ResponseEntity<ElementsCodeDTO> getElementsAsCode(@PathVariable @Parameter(description = "Chain id") String chainId) {
        return ResponseEntity.ok(elementMapper.elementsCodeToDTO(elementsCodeviewService.getElementsAsCode(chainId)));
    }

    @Deprecated(forRemoval = true, since = "24.2")
    @PostMapping("/code")
    @Operation(description = "Save all elements from the chain in code representation")
    public ResponseEntity<List<ElementResponse>> saveElementsFromCode(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                                      @RequestBody @Parameter(description = "Code representation from all elements in a chain") ElementsCodeDTO elementsYaml) {
        List<ChainElement> elements = elementsCodeviewService.saveElementsAsCode(chainId, elementsYaml.getCode());
        List<ElementResponse> responses = elementMapper.toElementResponses(elements);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/properties/used")
    @Operation(description = "Get used exchange properties in the chain")
    public ResponseEntity<List<UsedProperty>> getElementsUsedProperties(@PathVariable @Parameter(description = "Chain id") String chainId) {
        return ResponseEntity.ok(usedPropertiesAnalyzer.getUsedProperties(chainId));
    }

    @PostMapping
    @Operation(description = "Create element for the chain")
    public ResponseEntity<ChainDiffResponse> createElement(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                           @RequestBody @Parameter(description = "Create element request object") CreateElementRequest createRequest) {
        log.info("Request to add new element to chain with id: {}", chainId);
        ChainDiff chainDiff = transferableElementService.create(chainId, createRequest);
        ChainDiffResponse response = chainDiffMapper.asResponse(chainDiff);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/groups")
    @Operation(description = "Wrap specific elements from the chain into a group container")
    public ResponseEntity<?> createGroup(@PathVariable @Parameter(description = "Chain id") String chainId,
                                         @RequestBody @Parameter(description = "Element ids of the chain separated by comma") List<String> elements) {
        log.info("Request to wrap elements {} in group", elements);
        try {
            var containerEntity = transferableElementService.group(chainId, elements);
            var response = elementMapper.toElementResponse(containerEntity);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/clone")
    @Operation(description = "Copy specified elements to a specified container")
    public ResponseEntity<List<ElementResponse>> cloneElements(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                               @RequestBody @Parameter(description = "Copy element requests") List<CloneElementRequest> requests) {
        log.info("Request to clone element with id: {}", chainId);
        List<ElementResponse> response = new ArrayList<>(requests.size());

        for (CloneElementRequest request : requests) {
            ChainElement element = transferableElementService.clone(request.getId(), request.getParent());
            response.add(elementMapper.toElementResponse(element));
        }

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{elementId}")
    @Operation(description = "Change element in the chain")
    public ResponseEntity<ChainDiffResponse> patchElement(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                          @PathVariable @Parameter(description = "Element id") String elementId,
                                                          @RequestBody @Parameter(description = "Change element request object") PatchElementRequest patchElementRequest) {
        log.info("Request for partial update for element {} in chain {}", elementId, chainId);
        ChainDiff chainDiff = new ChainDiff();

        ChainElement element = transferableElementService.findById(elementId);
        ChainElement parentElement = element.getParent();
        String newParentId = patchElementRequest.getParentElementId();
        if (parentElement != null) {
            if (!StringUtils.equals(parentElement.getId(), newParentId)) {
                transferableElementService.changeParent(element, newParentId);
            } else {
                chainDiff.merge(transferableElementService.updateRelativeProperties(element, patchElementRequest.getProperties()));
            }
        }

        elementMapper.patch(element, patchElementRequest);

        transferableElementService.validateElementProperties(element);

        transferableElementService.save(element);
        chainDiff.addUpdatedElement(element);

        for (ChainElement updatedElement : chainDiff.getUpdatedElements()) {
            actionLogger.logAction(ActionLog.builder()
                    .entityType(EntityType.ELEMENT)
                    .entityId(updatedElement.getId())
                    .entityName(updatedElement.getName())
                    .parentType(EntityType.CHAIN)
                    .parentId(chainId)
                    .parentName(element.getChain() == null ? null : element.getChain().getName())
                    .operation(LogOperation.UPDATE)
                    .build());
        }

        return ResponseEntity.ok(chainDiffMapper.asResponse(chainDiff));
    }

    @PostMapping("/transfer")
    @Operation(description = "Move element from one container and/or swimlane to another")
    public ResponseEntity<ChainDiffResponse> transferElement(
            @PathVariable @Parameter(description = "Chain id") String chainId,
            @RequestBody @Parameter(description = "Transfer element request object") TransferElementRequest transferElementRequest
    ) {
        ChainDiff chainDiff = transferableElementService.transfer(chainId, transferElementRequest);
        ChainDiffResponse response = chainDiffMapper.asResponse(chainDiff);
        return ResponseEntity.ok(response);
    }

    @Deprecated
    @DeleteMapping("/{elementId}")
    @Operation(description = "Delete specific element from the chain")
    public ResponseEntity<ChainDiffResponse> deleteElementById(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                               @PathVariable @Parameter(description = "Element id") String elementId) {
        log.info("Request to delete element with id: {} from chain {}", elementId, chainId);
        ChainDiff chainDiff = transferableElementService.deleteByIdAndUpdateUnsaved(elementId);
        ChainDiffResponse response = chainDiffMapper.asResponse(chainDiff);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("")
    @Operation(description = "Delete specified elements from the chain")
    public ResponseEntity<ChainDiffResponse> deleteElementsByIds(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                                 @RequestParam @Parameter(description = "List of element ids separated by comma") List<String> elementsIds) {
        log.info("Request to delete elements with ids: {} from chain {}", elementsIds, chainId);
        ChainDiff chainDiff = transferableElementService.deleteAllByIdsAndUpdateUnsaved(elementsIds);
        ChainDiffResponse response = chainDiffMapper.asResponse(chainDiff);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/groups/{groupId}")
    @Operation(description = "Delete specified group container and ungroup element within it")
    public ResponseEntity<List<ElementResponse>> ungroup(@PathVariable @Parameter(description = "Chain id") String chainId,
                                                         @PathVariable @Parameter(description = "Container group id") String groupId) {
        log.info("Request to delete group {} in chain {}", groupId, chainId);
        List<ChainElement> entityList = transferableElementService.ungroup(groupId);
        List<ElementResponse> response = elementMapper.toElementResponses(entityList);
        return ResponseEntity.ok(response);
    }

}
