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

package org.qubership.integration.platform.runtime.catalog.rest.v2.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.ActionLogSearchRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v2.dto.ActionLogSearchResponse;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping(value = "/v2/catalog/actions-log")
@CrossOrigin(origins = "*")
@Tag(name = "actions-log-controller-v2", description = "Actions Log Controller V2")
public class ActionsLogControllerV2 {
    private final ActionsLogService actionsLogService;

    @Autowired
    public ActionsLogControllerV2(ActionsLogService actionsLogService) {
        this.actionsLogService = actionsLogService;
    }

    @PostMapping(value = "", produces = "application/json")
    @Operation(description = "Get action logs with offset/limit pagination")
    public ResponseEntity<ActionLogSearchResponse> findBySearchRequest(
            @RequestBody @Parameter(description = "Search request") ActionLogSearchRequest request) {
        return ResponseEntity.ok(actionsLogService.findByPagedSearchRequest(request));
    }
}
