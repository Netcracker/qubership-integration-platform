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

package org.qubership.integration.platform.variables.management.rest.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.variables.management.service.exportimport.ActionsLogExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/v1/actions-log/export")
@Tag(name = "actions-log-export-controller", description = "Actions Log Export Controller")
public class ActionsLogExportController {
    private final ActionsLogExportService actionsLogExportService;

    @Autowired
    public ActionsLogExportController(ActionsLogExportService actionsLogExportService) {
        this.actionsLogExportService = actionsLogExportService;
    }

    @Operation(description = "Export action log as a file")
    @GetMapping(value = "")
    public ResponseEntity<Object> export(@RequestParam @Parameter(description = "Timestamp from") Long actionTimeFrom,
                                         @RequestParam @Parameter(description = "Timestamp to") Long actionTimeTo) {
        log.info("Request to export actions log");
        byte[] document = this.actionsLogExportService.exportAsExcelDocument(new Timestamp(actionTimeFrom), new Timestamp(actionTimeTo));
        return asResponse(document);
    }

    private ResponseEntity<Object> asResponse(byte[] document) {
        HttpHeaders header = new HttpHeaders();
        header.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=variables-management-actions-log.xlsx");
        header.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
        ByteArrayResource resource = new ByteArrayResource(document);
        return ResponseEntity.ok()
                .headers(header)
                .contentLength(resource.contentLength())
                .body(resource);
    }
}
