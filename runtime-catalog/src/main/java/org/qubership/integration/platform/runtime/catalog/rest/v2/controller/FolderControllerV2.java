package org.qubership.integration.platform.runtime.catalog.rest.v2.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.FolderMoveException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.*;
import org.qubership.integration.platform.runtime.catalog.rest.v2.mapper.ChainItemMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v2.mapper.FolderItemMapper;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.FolderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping(value = "/v2/folders", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "folder-controller-v-2", description = "Folder Controller V2")
public class FolderControllerV2 {
    private final FolderService folderService;
    private final ChainService chainService;
    private final FolderItemMapper folderItemMapper;
    private final ChainItemMapper chainItemMapper;

    @Autowired
    public FolderControllerV2(
            FolderService folderService,
            ChainService chainService,
            FolderItemMapper folderItemMapper,
            ChainItemMapper chainItemMapper
    ) {
        this.folderService = folderService;
        this.chainService = chainService;
        this.folderItemMapper = folderItemMapper;
        this.chainItemMapper = chainItemMapper;
    }

    @PostMapping
    @Operation(description = "Create a new folder")
    public ResponseEntity<FolderItem> create(
            @RequestBody
            @Parameter(description = "Folder creation request object")
            CreateFolderRequest request
    ) {
        log.info("Request to create new folder");
        var parentFolderId = request.getParentId();
        var folder = folderItemMapper.asFolder(request);
        folder = folderService.save(folder, parentFolderId);
        var folderItem = folderItemMapper.asFolderItem(folder);
        return ResponseEntity.ok(folderItem);
    }

    @GetMapping("/{id}")
    @Operation(description = "Get folder")
    public ResponseEntity<FolderItem> getFolder(
            @PathVariable
            @Parameter(description = "Folder ID")
            String id
    ) {
        log.debug("Request to get folder by id: {}", id);
        var folder = folderService.findById(id);
        var folderItem = folderItemMapper.asFolderItem(folder);
        return ResponseEntity.ok(folderItem);
    }

    @GetMapping("/{id}/path")
    @Operation(description = "Get path to folder")
    public ResponseEntity<List<FolderItem>> getFolderPath(
            @PathVariable
            @Parameter(description = "Folder ID")
            String id
    ) {
        log.debug("Request to get path to folder with id: {}", id);
        List<FolderItem> path = folderService.getPathToFolder(id)
                .stream()
                .map(folderItemMapper::asFolderItem)
                .toList();
        return ResponseEntity.ok(path);
    }

    @GetMapping("/path")
    @Operation(description = "Get path to folder by name")
    public ResponseEntity<List<FolderItem>> getFolderPathByName(
            @RequestParam
            @Parameter(description = "Folder name")
            String name
    ) {
        log.debug("Request to get path to folder with name: {}", name);
        List<FolderItem> path = folderService.getPathToFolderByName(name)
                .stream()
                .map(folderItemMapper::asFolderItem)
                .toList();
        return ResponseEntity.ok(path);
    }

    @PutMapping("/{id}")
    @Operation(description = "Update folder")
    public ResponseEntity<FolderItem> updateFolder(
            @PathVariable
            @Parameter(description = "Folder ID")
            String id,

            @RequestBody
            @Parameter(description = "Folder update request object")
            UpdateFolderRequest request
    ) {
        log.info("Request to update folder with id: {}", id);
        var parentFolderId = request.getParentId();
        var folder = folderItemMapper.asFolder(request);
        folder = folderService.update(folder, id, parentFolderId);
        var folderItem = folderItemMapper.asFolderItem(folder);
        return ResponseEntity.ok(folderItem);
    }

    @DeleteMapping("/{id}")
    @Operation(description = "Delete folder")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable
            @Parameter(description = "Folder ID")
            String id
    ) {
        log.info("Request to delete folder with id: {}", id);
        folderService.deleteById(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @PostMapping("/list")
    @Operation(description = "List folder content")
    public ResponseEntity<List<? extends CatalogItem>> listFolder(
            @RequestParam(required = false)
            @Parameter(description = "Folder ID")
            String id,

            @RequestBody
            @Parameter(description = "List folder request object")
            ListFolderRequest request
    ) {
        List<Folder> foundFolders = folderService.findByRequest(request);
        List<Chain> foundChains = chainService.findByRequest(request);

        List<? extends CatalogItem> result = Stream.concat(
                foundFolders.stream().map(folderItemMapper::asFolderItem),
                foundChains.stream().map(chainItemMapper::asChainItem)
        ).toList();

        return ResponseEntity.ok(result);
    }

    @PostMapping("/move")
    @Operation(description = "Move folder")
    public ResponseEntity<FolderItem> moveFolder(
            @RequestBody
            @Parameter(description = "Move folder request object")
            MoveFolderRequest request
    ) throws FolderMoveException {
        log.info("Request to move folder with id: {}, target folder id: {}", request.getId(), request.getTargetId());
        var folder = folderService.move(request.getId(), request.getTargetId());
        var folderItem = folderItemMapper.asFolderItem(folder);
        return ResponseEntity.ok(folderItem);
    }

    @PostMapping("/bulk-delete")
    @Operation(description = "Delete folders")
    public ResponseEntity<Void> bulkDeleteFolders(
            @RequestBody
            @Parameter(description = "List of folder IDs")
            List<String> ids
    ) {
        log.info("Request to bulk delete folders");
        folderService.deleteByIds(ids);
        return ResponseEntity.noContent().build();
    }
}
