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

package org.qubership.integration.platform.designtime.catalog.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.exception.ChainDifferenceClientException;
import org.qubership.integration.platform.catalog.mapping.EntityDiffResponseMapper;
import org.qubership.integration.platform.catalog.model.dto.chain.EntityDifferenceResponse;
import org.qubership.integration.platform.catalog.model.dto.system.UsedSystem;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.service.difference.ChainDifferenceRequest;
import org.qubership.integration.platform.catalog.service.difference.ChainDifferenceService;
import org.qubership.integration.platform.catalog.service.difference.EntityDifferenceResult;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.chain.*;
import org.qubership.integration.platform.designtime.catalog.rest.v1.mapping.ChainLabelsMapper;
import org.qubership.integration.platform.designtime.catalog.rest.v1.mapping.ChainMapper;
import org.qubership.integration.platform.designtime.catalog.rest.v1.mapping.MigratedChainMapper;
import org.qubership.integration.platform.designtime.catalog.service.ChainService;
import org.qubership.integration.platform.designtime.catalog.service.migration.ChainMigrationService;
import org.qubership.integration.platform.designtime.catalog.service.migration.MigratedChain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


@Slf4j
@RestController
@RequestMapping(value = "/v1/chains", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "chain-controller", description = "Chain Controller")
public class ChainController {

    private final ChainService chainService;
    private final ChainDifferenceService chainDifferenceService;
    private final ChainMapper chainMapper;
    private final ChainMigrationService chainMigrationService;
    private final MigratedChainMapper migratedChainMapper;
    private final ChainLabelsMapper chainLabelsMapper;
    private final EntityDiffResponseMapper entityDiffResponseMapper;

    @Autowired
    public ChainController(
            ChainService chainService,
            ChainDifferenceService chainDifferenceService,
            ChainMapper chainMapper,
            ChainMigrationService chainMigrationService,
            MigratedChainMapper migratedChainMapper,
            ChainLabelsMapper chainLabelsMapper,
            EntityDiffResponseMapper entityDiffResponseMapper
    ) {
        this.chainService = chainService;
        this.chainDifferenceService = chainDifferenceService;
        this.chainMapper = chainMapper;
        this.chainMigrationService = chainMigrationService;
        this.migratedChainMapper = migratedChainMapper;
        this.chainLabelsMapper = chainLabelsMapper;
        this.entityDiffResponseMapper = entityDiffResponseMapper;
    }

    @GetMapping
    @Operation(description = "Get list of all chains and folders without chain elements")
    public ResponseEntity<List<ChainResponse>> findAllLight() {
        if (log.isDebugEnabled()) {
            log.debug("Request to receive all previews");
        }
        List<Chain> chains = chainService.findAll();
        List<ChainResponse> chainsDto = chainMapper.asChainResponseLight(chains);
        return ResponseEntity.ok(chainsDto);
    }

    @GetMapping("/{chainId}")
    @Operation(description = "Find chain with its elements")
    public ResponseEntity<ChainDTO> findById(@PathVariable @Parameter(description = "Chain id") String chainId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to receive info about chain with id: {}", chainId);
        }
        Chain chain = chainService.findById(chainId);

        ChainDTO response = chainMapper.asDTO(chain);
        Map<String, String> navigationPath = chainService.provideNavigationPath(chainId);
        response.setNavigationPath(navigationPath);
        response.setContainsDeprecatedContainers(chainMigrationService.containsDeprecatedContainers(chain));
        response.setContainsDeprecatedElements(chainService.containsDeprecatedElements(chain));
        response.setContainsUnsupportedElements(chainService.containsUnsupportedElements(chain));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{systemId}/{operationId}")
    @Operation(description = "Find chains (without elements) which elements is using specified service and operation")
    public ResponseEntity<List<ChainResponse>> findBySystemIdAndOperationId(@PathVariable @Parameter(description = "Service id") String systemId,
                                                                            @PathVariable @Parameter(description = "Specification operation id") String operationId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find chains by system {} and operation {}", systemId, operationId);
        }
        List<Chain> chains = chainService.findBySystemAndOperationId(systemId, operationId);
        List<ChainResponse> chainsDto = chainMapper.asChainResponseLight(chains);
        return ResponseEntity.ok(chainsDto);
    }

    @GetMapping("/{systemId}/model/{modelId}")
    @Operation(description = "Find chains (without elements) which elements is using specified service and specification")
    public ResponseEntity<List<ChainResponse>> findBySystemIdAndModelId(@PathVariable @Parameter(description = "Service id") String systemId,
                                                                        @PathVariable @Parameter(description = "Specification id") String modelId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find chains by system {} and model {}", systemId, modelId);
        }
        List<Chain> chains = chainService.findBySystemAndModelId(systemId, modelId);
        List<ChainResponse> chainsDto = chainMapper.asChainResponseLight(chains);
        return ResponseEntity.ok(chainsDto);
    }

    @GetMapping("/{systemId}/specificationGroup/{groupId}")
    @Operation(description = "Find chains (without elements) which elements is using specified service and specification group")
    public ResponseEntity<List<ChainResponse>> findBySystemIdAndSpecificationGroupId(
            @PathVariable @Parameter(description = "Service id") String systemId,
            @PathVariable @Parameter(description = "Specification group id") String groupId
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find chains by system {} and specification group {}", systemId, groupId);
        }
        List<Chain> chains = chainService.findBySystemAndGroupId(systemId, groupId);
        List<ChainResponse> chainsDto = chainMapper.asChainResponseLight(chains);
        return ResponseEntity.ok(chainsDto);
    }

    @GetMapping("/{systemId}/specificationGroup")
    @Operation(description = "Find chains (without elements) which elements is using specified service grouped by specification group")
    public ResponseEntity<List<ChainsBySpecificationGroup>> findBySystemIdGroupBySpecificationGroup(
            @PathVariable @Parameter(description = "Service id") String systemId
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find chains by system {} with specification groups information", systemId);
        }
        Map<String, List<Chain>> specGroupsChains = chainService.findBySystemIdGroupBySpecificationGroup(systemId);
        List<ChainsBySpecificationGroup> chainsDto = chainMapper.asChainsBySpecificationGroup(specGroupsChains);
        return ResponseEntity.ok(chainsDto);
    }

    @GetMapping("/systems/{systemId}")
    @Operation(description = "Find chains (with or without elements) which elements is using specified service")
    public ResponseEntity<Object> findBySystemId(@PathVariable @Parameter(description = "Service id") String systemId,
                                                 @RequestParam(required = false, defaultValue = "light") @Parameter(description = "If \"light\" was passed - response will be without chain elements. Otherwise elements will be included in response.") String mode) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find chains by system {}", systemId);
        }
        if (mode.equals("light")) {
            List<Chain> chains = chainService.findBySystemId(systemId);
            List<ChainResponse> chainsDto = chainMapper.asChainResponseLight(chains);
            return ResponseEntity.ok(chainsDto);
        }
        List<Chain> chains = chainService.findBySystemId(systemId);
        List<ChainDTO> chainsDto = chainMapper.asDTO(chains);
        return ResponseEntity.ok(chainsDto);
    }

    @GetMapping("/used-systems")
    @Operation(description = "Get services and specifications used by specified chains")
    public ResponseEntity<List<UsedSystem>> getUsedSystemIdsByChainIds(@RequestParam(required = false) @Parameter(description = "Chain ids separated by comma") List<String> chainIds) {
        return ResponseEntity.ok(chainService.getUsedSystemIdsByChainIds(chainIds));
    }

    @GetMapping("/names")
    @Operation(description = "Get map of chain ids and related chain name")
    public ResponseEntity<Map<String, String>> getNamesMapByChainIds(@RequestParam @Parameter(description = "Chain ids separated by comma") Set<String> chainIds) {
        return ResponseEntity.ok(chainService.getNamesMapByChainIds(chainIds));
    }

    @RequestMapping(method = RequestMethod.HEAD, value = "/{chainId}")
    @Operation(description = "Find chain by id if it exists", extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}))
    public ResponseEntity<Chain> findExistingChain(@PathVariable String chainId) {
        return ResponseEntity.ok(chainService.findById(chainId));
    }

    @PostMapping
    @Operation(description = "Create a new chain")
    public ResponseEntity<ChainResponse> create(@RequestBody @Parameter(description = "Chain creation request object") ChainRequest request) {
        String parentFolderId = request.getParentId();
        log.info("Request to create new chain under folder: {}", parentFolderId);
        Chain chain = chainMapper.asEntity(request);
        chain = chainService.save(chain, parentFolderId);
        ChainResponse response = chainMapper.asChainResponseLight(chain);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{chainId}")
    @Operation(description = "Update existing chain")
    public ResponseEntity<ChainDTO> update(@PathVariable @Parameter(description = "Chain id") String chainId,
                                           @RequestBody @Parameter(description = "Chain creation request object") ChainRequest request) {
        log.info("Request to update chain with id: {}", chainId);
        String parentFolderId = request.getParentId();
        Chain chain = chainService.findById(chainId);
        chainMapper.mergeWithoutLabels(chain, request);
        chain = chainService.update(chain, chainLabelsMapper.asEntities(request.getLabels()), parentFolderId);
        ChainDTO response = chainMapper.asDTO(chain);
        Map<String, String> navigationPath = chainService.provideNavigationPath(chainId);
        response.setNavigationPath(navigationPath);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{chainId}")
    @Operation(description = "Delete chain")
    public ResponseEntity<Void> deleteById(@PathVariable @Parameter(description = "Chain id") String chainId) {
        log.info("Request to remove chain with id: {}", chainId);
        chainService.deleteById(chainId);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/{chainId}/copy")
    @Operation(description = "Copy existing chain to a specified folder")
    public ResponseEntity<ChainResponse> copy(@PathVariable @Parameter(description = "Chain id") String chainId,
                                              @RequestParam(required = false, defaultValue = "#{null}") @Parameter(description = "Target folder id") String targetFolderId) {
        log.info("Request to copy chain with id: {}, target folder id: {}", chainId, targetFolderId);
        Chain chainCopy = chainService.copy(chainId, targetFolderId);
        ChainResponse response = chainMapper.asChainResponseLight(chainCopy);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{chainId}/duplicate")
    @Operation(description = "Copy existing chain to the same folder")
    public ResponseEntity<ChainResponse> duplicate(@PathVariable @Parameter(description = "Chain id") String chainId) {
        log.info("Request to duplicate chain with id: {}", chainId);
        Chain chainCopy = chainService.duplicate(chainId);
        ChainResponse response = chainMapper.asChainResponseLight(chainCopy);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{chainId}/move")
    @Operation(description = "Move existing chain to a specified folder")
    public ResponseEntity<ChainResponse> move(@PathVariable @Parameter(description = "Chain id") String chainId,
                                              @RequestParam(required = false, defaultValue = "#{null}") @Parameter(description = "Target folder id") String targetFolderId) {
        log.info("Request to move chain with id: {}, target folder id: {}", chainId, targetFolderId);
        Chain chainCopy = chainService.move(chainId, targetFolderId);
        ChainResponse response = chainMapper.asChainResponseLight(chainCopy);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{chainId}/migrate")
    @Operation(description = "Initiate migrate process for a chain to replace old deprecated elements with new ones")
    public ResponseEntity<MigratedChainDTO> migrate(@PathVariable @Parameter(description = "Chain id") String chainId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to migrate chain with id: {}", chainId);
        }
        MigratedChain migratedChain = chainMigrationService.migrateChain(chainId);
        MigratedChainDTO response = migratedChainMapper.asDTO(migratedChain);
        response.getChain().setContainsDeprecatedElements(chainService.containsDeprecatedElements(migratedChain.getChain()));
        response.getChain().setContainsUnsupportedElements(chainService.containsUnsupportedElements(migratedChain.getChain()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/count")
    @Operation(description = "Get chains total count")
    public ResponseEntity<ChainsCountResponse> getChainsTotalCount() {
        return ResponseEntity.ok(new ChainsCountResponse(chainService.getChainsCount()));
    }

    @PostMapping("/diff")
    @Operation(description = "Find differences between chains/snapshots", responses = @ApiResponse(
            responseCode = "200",
            content = @Content(schema = @Schema(implementation = EntityDifferenceResponse.class))))
    public ResponseEntity<EntityDifferenceResponse> difference(
            @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Chain difference request",
                    content = @Content(schema = @Schema(implementation = ChainDifferenceRequest.class)))
            @Validated
            ChainDifferenceRequest chainDiffRequest,
            BindingResult bindingResult
    ) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "Request to get differences between entities: {}, {}",
                    Optional.ofNullable(chainDiffRequest.getLeftSnapshotId()).orElse(chainDiffRequest.getLeftChainId()),
                    Optional.ofNullable(chainDiffRequest.getRightSnapshotId()).orElse(chainDiffRequest.getRightChainId())
            );
        }

        if (bindingResult.hasFieldErrors()) {
            throw new ChainDifferenceClientException("Diff request validation failed: " + bindingResult.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .toList());
        }

        EntityDifferenceResult diffResult = chainDifferenceService.findChainsDifferences(chainDiffRequest);
        return ResponseEntity.ok(entityDiffResponseMapper.asResponse(diffResult));
    }
}
