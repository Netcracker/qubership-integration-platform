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
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationAlertsSet;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationState;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.diagnostic.DiagnosticValidationDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.diagnostic.DiagnosticValidationFilterDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.diagnostic.ValidationStatusDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DiagnosticValidationMapper;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.DiagnosticService;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.AbstractValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping(value = "/v1/catalog/diagnostic", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "*")
@Tag(name = "diagnostic-controller", description = "Diagnostic Controller")
public class DiagnosticController {
    private final DiagnosticService diagnosticService;
    private final DiagnosticValidationMapper validationMapper;

    @Autowired
    public DiagnosticController(DiagnosticService diagnosticService, DiagnosticValidationMapper validationMapper) {
        this.diagnosticService = diagnosticService;
        this.validationMapper = validationMapper;
    }

    @PostMapping("/validations")
    @Operation(description = "Search diagnostic validations")
    public ResponseEntity<List<DiagnosticValidationDTO>> searchValidations(
            @RequestBody(required = false) @Parameter(description = "Validation search request object")
            DiagnosticValidationFilterDTO filterRequest
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to search diagnostic validations");
        }

        List<Pair<AbstractValidation, ValidationAlertsSet>> validations =
                diagnosticService.getFilteredValidations(filterRequest);
        Map<String, ValidationStatus> statuses = diagnosticService.getCurrentStatuses();
        List<DiagnosticValidationDTO> result = new ArrayList<>(validations.size());

        for (Pair<AbstractValidation, ValidationAlertsSet> pair : validations) {
            AbstractValidation validation = pair.getLeft();
            ValidationAlertsSet alertsSet = pair.getRight();
            ValidationStatusDTO statusDTO = validationMapper.asStatusDTO(statuses.get(validation.getId()));

            result.add(validationMapper.asDTO(validation, statusDTO, alertsSet));
        }

        for (DiagnosticValidationDTO dto : result) {
            clearEntitiesByState(dto);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/validations/{validationId}")
    @Operation(description = "Get diagnostic validations by id")
    public ResponseEntity<DiagnosticValidationDTO> getValidationById(
            @PathVariable("validationId") @Parameter(description = "Validation id") String validationId
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get all diagnostic validation: {}", validationId);
        }
        Pair<AbstractValidation, ValidationAlertsSet> pair = diagnosticService.getValidationById(validationId);
        ValidationStatusDTO status = validationMapper.asStatusDTO(diagnosticService.getCurrentStatus(validationId));
        ValidationAlertsSet alertsSet = pair.getRight();
        DiagnosticValidationDTO dto = validationMapper.asDTO(pair.getLeft(), status, alertsSet);
        clearEntitiesByState(dto);
        return ResponseEntity.ok(dto);
    }

    @PatchMapping("/validations")
    @Operation(description = "Run diagnostic validations")
    public ResponseEntity<Void> runValidations(
            @RequestParam(required = false, defaultValue = "") @Parameter(description = "List of validation IDs that need to be run."
                    + " If the parameter is empty, then all validations will be run") Set<String> validationIds
    ) {
        log.info("Request to start validations processing for: {}",
                validationIds == null || validationIds.isEmpty() ? "all validations" : validationIds);
        diagnosticService.runValidationsAsync(validationIds);
        return ResponseEntity.accepted().build();
    }

    // clear entities for failed and in-progress validations
    private static void clearEntitiesByState(DiagnosticValidationDTO dto) {
        if (dto.getStatus().getState() == ValidationState.FAILED
                || dto.getStatus().getState() == ValidationState.IN_PROGRESS
                || dto.getStatus().getState() == ValidationState.NOT_STARTED
        ) {
            dto.setAlertsCount(0);
            dto.setChainEntities(dto.getChainEntities() == null ? null : Collections.emptyList());
        }
    }
}
