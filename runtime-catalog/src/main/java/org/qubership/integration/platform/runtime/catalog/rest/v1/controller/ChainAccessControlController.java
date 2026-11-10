package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElementSearchCriteria;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRolesResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.UpdateRolesRequest;
import org.qubership.integration.platform.runtime.catalog.service.ChainRolesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping(value = {
    "/v1/catalog/chains/access-control",
    "/api/v1/cip/catalog/chains/access-control"
}, produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "chain-access-control-controller", description = "Chain Access Control Controller")
public class ChainAccessControlController {

    private final ChainRolesService chainRolesService;

    @Autowired
    public ChainAccessControlController(ChainRolesService chainRolesService) {
        this.chainRolesService = chainRolesService;
    }

    @PostMapping("")
    @Operation(
            operationId = "findBySearchRequest",
            description = "Get all chains with http trigger roles"
    )
    public ResponseEntity<ChainRolesResponse> findBySearchRequest(
            @RequestBody @Parameter(description = "Search criteria for chain element") ChainElementSearchCriteria request,
            @RequestParam(required = false, defaultValue = "false")
            @Parameter(description = "Whether include only http triggers with selected implemented service")
            boolean isImplementedOnly
    ) {
        ChainRolesResponse response = chainRolesService.findAllChainByHttpTrigger(request, isImplementedOnly);
        return ResponseEntity.ok(response);
    }

    @PutMapping("")
    @Operation(
            operationId = "updateRoles",
            description = "Make a bulk update on roles configuration for http triggers"
    )
    @ApiResponse(responseCode = "204", description = "No Content")
    public ResponseEntity<Void> updateRoles(
            @RequestBody
            @Parameter(description = "Http trigger roles update request object")
            List<@Valid UpdateRolesRequest> request
    ) {
        chainRolesService.updateRoles(request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/redeploy")
    @Operation(
            operationId = "bulkRedeploy",
            description = "Make a bulk redeploy for chains specified in the request"
    )
    @ApiResponse(responseCode = "204", description = "No Content")
    public ResponseEntity<Void> bulkRedeploy(
            @RequestBody @Parameter(description = "Ids of the chains to redeploy") List<String> chainIds
    ) {
        chainRolesService.redeploy(chainIds);
        return ResponseEntity.noContent().build();
    }
}
