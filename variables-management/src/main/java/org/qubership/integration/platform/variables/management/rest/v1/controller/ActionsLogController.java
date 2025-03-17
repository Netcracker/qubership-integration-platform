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
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.variables.management.rest.v1.dto.actionlog.ActionLogResponse;
import org.qubership.integration.platform.variables.management.rest.v1.dto.actionlog.ActionLogSearchCriteria;
import org.qubership.integration.platform.variables.management.rest.v1.mapper.ActionsLogMapper;
import org.qubership.integration.platform.variables.management.service.ActionsLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(value = "/v1/actions-log")
@CrossOrigin(origins = "*")
@Tag(name = "actions-log-controller", description = "Actions Log Controller")
public class ActionsLogController {
    private final ActionsLogService actionsLogService;
    private final ActionsLogMapper actionsLogMapper;

    @Autowired
    public ActionsLogController(ActionsLogService actionsLogService, ActionsLogMapper actionsLogMapper) {
        this.actionsLogService = actionsLogService;
        this.actionsLogMapper = actionsLogMapper;
    }

    @PostMapping(value = "", produces = "application/json")
    @Operation(description = "Get action logs")
    public ResponseEntity<ActionLogResponse> findBySearchRequest(@RequestBody @Parameter(description = "Search request") ActionLogSearchCriteria request) {
        Pair<Long, List<ActionLog>> actions = actionsLogService.findBySearchRequest(request);
        return ResponseEntity.ok(actionsLogMapper.asResponse(actions.getLeft(), actions.getRight()));
    }
}
