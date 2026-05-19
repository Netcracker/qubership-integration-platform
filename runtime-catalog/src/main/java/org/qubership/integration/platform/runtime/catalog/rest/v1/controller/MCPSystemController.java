package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemFilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemResponseDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.MCPSystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.MCPSystemService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.MCPSystemImportExportService;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static java.util.Objects.isNull;

@Slf4j
@ComponentScan
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/v1/catalog/mcp-system", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "mcp-system-controller", description = "MCP System Controller")
public class MCPSystemController {
    private final MCPSystemService mcpSystemService;
    private final MCPSystemMapper mcpSystemMapper;
    private final MCPSystemImportExportService mcpSystemImportExportService;

    @Autowired
    public MCPSystemController(
            MCPSystemService mcpSystemService,
            MCPSystemMapper mcpSystemMapper,
            MCPSystemImportExportService mcpSystemImportExportService
    ) {
        this.mcpSystemService = mcpSystemService;
        this.mcpSystemMapper = mcpSystemMapper;
        this.mcpSystemImportExportService = mcpSystemImportExportService;
    }

    @GetMapping
    @Operation(description = "Get all MCP systems")
    public ResponseEntity<List<MCPSystemResponseDTO>> getAll(
            @RequestParam(name = "withChains", defaultValue = "false")
            boolean withChains
    ) {
        log.debug("Request to get all MCP systems");
        List<MCPSystem> systems = mcpSystemService.findAll(withChains);
        List<MCPSystemResponseDTO> dtos = mcpSystemMapper.toResponseDtos(systems);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    @Operation(description = "Get MCP system by ID")
    public ResponseEntity<MCPSystemResponseDTO> getById(
            @PathVariable String id
    ) {
        log.debug("Request to get MCP system by ID: {}", id);
        return mcpSystemService.findById(id).map(system -> {
            MCPSystemResponseDTO dto = mcpSystemMapper.toResponseDto(system);
            return ResponseEntity.ok(dto);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(description = "Create MCP system")
    public ResponseEntity<MCPSystemResponseDTO> create(
            @RequestBody MCPSystemRequestDTO requestDTO
    ) {
        log.debug("Request to create MCP system: {}", requestDTO);
        MCPSystem system = mcpSystemService.create(requestDTO);
        MCPSystemResponseDTO dto = mcpSystemMapper.toResponseDto(system);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    @Operation(description = "Update MCP system")
    public ResponseEntity<MCPSystemResponseDTO> update(
            @PathVariable String id,
            @RequestBody MCPSystemRequestDTO requestDTO
    ) {
        log.debug("Request to update MCP system: {}", requestDTO);
        MCPSystem system = mcpSystemService.update(id, requestDTO);
        MCPSystemResponseDTO dto = mcpSystemMapper.toResponseDto(system);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    @Operation(description = "Delete MCP system")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        log.debug("Request to delete MCP system: {}", id);
        mcpSystemService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/filter")
    @Operation(description = "Filter MCP systems")
    public ResponseEntity<List<MCPSystemResponseDTO>> filter(
            @RequestBody MCPSystemFilterRequestDTO requestDTO
    ) {
        log.debug("Request to filter MCP systems: {}", requestDTO);
        List<MCPSystem> systems = mcpSystemService.filter(requestDTO.getSearchString(), requestDTO.getFilters());
        List<MCPSystemResponseDTO> dtos = mcpSystemMapper.toResponseDtos(systems);
        return ResponseEntity.ok(dtos);
    }

    @PostMapping(value = "/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(description = "Export MCP services")
    public ResponseEntity<Object> export(
            @RequestBody List<String> ids
    ) {
        byte[] data = mcpSystemImportExportService.export(ids);
        if (isNull(data)) {
            return ResponseEntity.noContent().build();
        }

        return ExportImportUtils.convertFileToResponse(data, ExportImportUtils.generateArchiveExportName());
    }

    @PostMapping("/import")
    @Operation(
            extensions = @Extension(
                    properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}
            ),
            description = "Import MCP services from a file"
    )
    public ResponseEntity<List<ImportSystemResult>> importSystems(
        @RequestParam("file")
        @Parameter(description = "File")
        MultipartFile file,

        @RequestParam(required = false)
        @Parameter(description = "List of system IDs")
        List<String> ids
    ) {
        List<ImportSystemResult> result = mcpSystemImportExportService.importSystems(file, ids);
        if (result.isEmpty()) {
            return ResponseEntity.noContent().build();
        } else {
            HttpStatus responseCode = result.stream().anyMatch(dto -> dto.getStatus().equals(ImportSystemStatus.ERROR))
                    ? HttpStatus.MULTI_STATUS
                    : HttpStatus.OK;
            return ResponseEntity.status(responseCode).body(result);
        }
    }

    @PostMapping("/import/preview")
    @Operation(description = "Get preview on what will be imported from file")
    public ResponseEntity<List<ImportSystemResult>> getImportPreview(
            @RequestParam("file")
            @Parameter(description = "File")
            MultipartFile file
    ) {
        List<ImportSystemResult> result = mcpSystemImportExportService.getImportPreview(file);
        return result.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok().body(result);
    }
}
