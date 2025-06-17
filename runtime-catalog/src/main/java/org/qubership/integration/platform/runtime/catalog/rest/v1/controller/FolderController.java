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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.FolderMoveException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.FoldableEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainSearchRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.logging.properties.ChainLoggingPropertiesSet;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder.*;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ChainMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.FolderMapper;
import org.qubership.integration.platform.runtime.catalog.service.ChainRuntimePropertiesService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.FolderService;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping(value = "/v1/folders", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "folder-controller", description = "Folder Controller")
public class FolderController {
    private final FolderService folderService;
    private final FolderMapper folderMapper;
    private final ChainMapper chainMapper;

    private final ChainService chainService;
    private final ChainFinderService chainFinderService;
    private final ChainRuntimePropertiesService propertiesService;

    @Autowired
    public FolderController(FolderService folderService,
                            FolderMapper folderMapper,
                            ChainService chainService,
                            ChainFinderService chainFinderService,
                            ChainMapper chainMapper,
                            ChainRuntimePropertiesService propertiesService) {
        this.folderService = folderService;
        this.folderMapper = folderMapper;
        this.chainService = chainService;
        this.chainFinderService = chainFinderService;
        this.chainMapper = chainMapper;
        this.propertiesService = propertiesService;
    }

    @GetMapping
    @Operation(description = "Get root folder")
    public ResponseEntity<List<? extends FolderItemResponse>> findRootFolder(
            @RequestParam(required = false) @Parameter(description = "Content filter object for a folder item request") FolderContentFilter filter,
            @RequestParam(required = false) @Parameter(description = "Pre-opened folder (if specified, data for this folder will be fetched as well as root folder") String openedFolderId
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find root folder. Content filter: {}.", filter);
        }
        List<Folder> foldersInRoot = folderService.findAllInRoot();
        List<Chain> chainsInRoot = chainFinderService.findInRoot(filter);

        List<FolderItemResponse> response = new ArrayList<>(getListResponse(chainsInRoot, foldersInRoot, false));

        if (filter == null && StringUtils.isNotEmpty(openedFolderId)) { // folder url navigation and filter are not compatible
            addOpenedFolderHierarchy(openedFolderId, response);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{folderId}")
    @Operation(description = "Get specific folder")
    public ResponseEntity<FolderResponse> findById(
            @PathVariable @Parameter(description = "Folder id") String folderId,
            @RequestParam(required = false) @Parameter(description = "Content filter object for a folder item request") FolderContentFilter filter
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find folder by id: {}", folderId);
        }
        var folder = folderService.findById(folderId);

        var folderResponse = folderMapper.asResponse(folder);
        var navigationPath = folderService.provideNavigationPath(folderId);
        folderResponse.setNavigationPath(navigationPath);

        Set<String> chainIds = chainFinderService.findChainsInFolder(folderId, filter).stream()
                .map(Chain::getId).collect(Collectors.toSet());
        List<FolderItemResponse> items = folderResponse.getItems().stream()
                .filter(item -> item.getItemType().equals(ItemType.FOLDER) || chainIds.contains(item.getId()))
                .collect(Collectors.toList());
        folderResponse.setItems(items);
        addRuntimeProperties(items);

        return ResponseEntity.ok(folderResponse);
    }

    @GetMapping("/{folderId}/chains")
    @Operation(description = "Get nested chains from specified folder")
    public ResponseEntity<List<ChainResponse>> findNestedChains(
            @PathVariable @Parameter(description = "Folder id") String folderId,
            @RequestParam(required = false) @Parameter(description = "Content filter object for a folder item request") FolderContentFilter filter
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find nested chains by folder id: {}. Content filter: {}.", folderId, filter);
        }
        List<Chain> chains = folderService.findNestedChains(folderId, filter);
        return ResponseEntity.ok(chainMapper.asChainResponseLight(chains));
    }

    @GetMapping("/{folderId}/elements")
    @Operation(description = "Get nested chains and folders from specified folder")
    public ResponseEntity<List<? extends FolderItemResponse>> findNestedElements(
            @PathVariable @Parameter(description = "Folder id") String folderId,
            @RequestParam(required = false) @Parameter(description = "Content filter object for a folder item request") FolderContentFilter filter
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find nested elements by folder id: {}. Content filter: {}.", folderId, filter);
        }
        List<Chain> chains = folderService.findNestedChains(folderId, null);
        List<Folder> folders = folderService.findNestedFolders(folderId);
        folders.add(folderService.findById(folderId));
        List<FolderItemResponse> response = new ArrayList<>(getListResponse(chains, folders, true));
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(description = "Create a new folder")
    public ResponseEntity<FolderResponse> create(@RequestBody @Parameter(description = "Folder creation request object") FolderItemRequest request) {
        log.info("Request to create new folder");
        var parentFolderId = request.getParentId();
        var folder = folderMapper.asEntity(request);
        folder = folderService.save(folder, parentFolderId);
        var response = folderMapper.asResponse(folder);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/search", produces = "application/json")
    @Operation(description = "Search from root folder with chains")
    public ResponseEntity<List<? extends FolderItemResponse>> searchRootFolderWithChains(@RequestBody @Parameter(description = "Folder search request object") ChainSearchRequestDTO chainSearchRequestDTO) {
        List<Chain> foundChains = chainService.searchChains(chainSearchRequestDTO);
        List<Folder> foundFolders = folderService.searchFolders(chainSearchRequestDTO);
        List<Chain> chainsInSubfolders = chainService.findAllChainsInFolders(foundFolders.stream().map(Folder::getId).toList());

        Set<Chain> chains = new TreeSet<>(Comparator.comparing(Chain::getId));
        chains.addAll(foundChains);
        chains.addAll(chainsInSubfolders);

        List<? extends FoldableEntity> entities = Stream.concat(chains.stream(), foundFolders.stream()).toList();
        List<Folder> relatedFolders = folderService.getFoldersHierarchically(entities);

        Set<Folder> folders = new TreeSet<>(Comparator.comparing(Folder::getId));
        folders.addAll(relatedFolders);
        folders.addAll(foundFolders);

        prepareSearchFilterResult(chains, folders);
        List<? extends FolderItemResponse> response = getListResponse(chains, folders, true);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/filter", produces = "application/json")
    @Operation(description = "Filter from root folder with chains")
    public ResponseEntity<List<? extends FolderItemResponse>> filterRootFolderWithChains(@RequestBody @Parameter(description = "Folder filter request object") List<FilterRequestDTO> filterRequestDTOList) {
        List<Chain> chains = chainService.findByFilterRequest(filterRequestDTOList);
        List<Folder> relatedFolders = folderService.getFoldersHierarchically(chains);

        prepareSearchFilterResult(chains, relatedFolders);
        List<? extends FolderItemResponse> response = getListResponse(chains, relatedFolders, true);
        return ResponseEntity.ok(response);
    }

    private void prepareSearchFilterResult(Collection<Chain> chains, Collection<Folder> folders) {
        Map<String, Folder> folderMap = folders.stream()
                .collect(Collectors.toMap(Folder::getId, Function.identity()));

        Stream.concat(folders.stream(), chains.stream()).forEach(entity -> {
            if (entity.getParentFolder() != null) {
                log.info("Find entity with parent folder: {}", entity.getName());
                Folder parentFolder = folderMap.get(entity.getParentFolder().getId());
                if (entity instanceof Chain chain) {
                    parentFolder.getChainList().add(chain);
                }
                if (entity instanceof Folder folder) {
                    parentFolder.getFolderList().add(folder);
                }
            }
        });
    }

    @PutMapping("/{folderId}")
    @Operation(description = "Update specified folder")
    public ResponseEntity<FolderResponse> update(@PathVariable @Parameter(description = "Folder id") String folderId,
                                                 @RequestBody @Parameter(description = "Folder modification request object") FolderItemRequest request) {
        log.info("Request to update folder with id: {}", folderId);
        var parentFolderId = request.getParentId();
        var folder = folderMapper.asEntity(request);
        folder = folderService.update(folder, folderId, parentFolderId);
        FolderResponse response = folderMapper.asResponse(folder);
        Map<String, String> navigationPath = folderService.provideNavigationPath(folderId);
        response.setNavigationPath(navigationPath);
        addRuntimePropertiesToChild(response);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{folderId}")
    @Operation(description = "Delete specified folder")
    public ResponseEntity<Void> deleteByIdIn(@PathVariable("folderId") @Parameter(description = "Folder id") String id) {
        log.info("Request to delete folder with id: {}", id);
        folderService.deleteById(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @PostMapping("/{folderId}/move")
    @Operation(description = "Move specified folder (change parent folder)")
    public ResponseEntity<FolderResponse> move(@PathVariable @Parameter(description = "Folder id") String folderId,
                                               @RequestParam(required = false, defaultValue = "#{null}") @Parameter(description = "Target parent folder id") String targetFolderId) throws FolderMoveException {
        log.info("Request to move folder with id: {}, target folder id: {}", folderId, targetFolderId);
        Folder folderCopy = folderService.move(folderId, targetFolderId);
        FolderResponse response = folderMapper.asResponse(folderCopy);
        addRuntimePropertiesToChild(response);
        return ResponseEntity.ok(response);
    }

    private void addRuntimePropertiesToChild(FolderResponse response) {
        addRuntimeProperties(response.getItems());
    }

    private void addRuntimeProperties(List<? extends FolderItemResponse> response) {
        response.forEach(item -> {
            if (item.getItemType() == ItemType.CHAIN) {
                ChainLoggingPropertiesSet props =
                        propertiesService.getRuntimeProperties(item.getId());
                item.setChainRuntimeProperties(props);
            }
        });
    }

    private List<? extends FolderItemResponse> getListResponse(
            Collection<Chain> chainSearchResult,
            Collection<Folder> relatedFolders,
            boolean includeItems
    ) {
        List<? extends FolderItemResponse> responseList =
                includeItems ? folderMapper.asSearchItemResponse(relatedFolders) : folderMapper.asFolderItemResponse(relatedFolders);
        responseList.forEach(response -> response.setItemType(ItemType.FOLDER));
        List<? extends FolderItemResponse> chainsResponse = chainMapper.asFolderItemResponse(chainSearchResult);
        chainsResponse.forEach(response -> response.setItemType(ItemType.CHAIN));

        List<FolderItemResponse> result = new ArrayList<>(responseList.size() + chainsResponse.size());
        result.addAll(responseList);
        result.addAll(chainsResponse);
        addRuntimeProperties(result);
        return result;
    }

    private void addOpenedFolderHierarchy(String openedFolderId, List<FolderItemResponse> response) {
        List<Folder> openedFolderRelatedFolders = folderService.findAllFoldersToRootParentFolder(openedFolderId);
        List<Chain> openedFolderRelatedChains = chainFinderService.findAllChainsToRootParentFolder(openedFolderId);
        Map<String, Folder> folderMap = openedFolderRelatedFolders.stream()
                .collect(Collectors.toMap(AbstractEntity::getId, Function.identity()));
        Map<String, Chain> chainMap = openedFolderRelatedChains.stream()
                .collect(Collectors.toMap(AbstractEntity::getId, Function.identity()));

        for (Folder folder : openedFolderRelatedFolders) {
            // replace parent folder with new (include items)
            if (folder.getParentFolder() == null) {
                response.removeIf(item -> item.getId().equals(folder.getId()));
            }
            // find the deepest folder end clear children
            if (hasExtraChild(folder.getFolderList(), folderMap, chainMap) || hasExtraChild(folder.getChainList(), folderMap, chainMap)) {
                folder.setFolderList(null);
                folder.setChainList(null);
            }
        }

        response.addAll(getListResponse(openedFolderRelatedChains, openedFolderRelatedFolders, true));
    }

    private boolean hasExtraChild(List<? extends FoldableEntity> entities, Map<String, Folder> folderMap, Map<String, Chain> chainMap) {
        for (FoldableEntity child : entities) {
            if ((child instanceof Folder && !folderMap.containsKey(child.getId()))
                    || (child instanceof Chain && !chainMap.containsKey(child.getId()))) {
                return true;
            }
        }
        return false;
    }
}
