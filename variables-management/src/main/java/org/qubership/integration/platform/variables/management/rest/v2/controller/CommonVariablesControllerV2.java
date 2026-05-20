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

package org.qubership.integration.platform.variables.management.rest.v2.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.variables.management.rest.v2.dto.variables.ImportVariablesResult;
import org.qubership.integration.platform.variables.management.service.CommonVariablesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/v2/common-variables")
@CrossOrigin(origins = "*")
@Validated
@Tag(name = "common-variables-controller-v-2", description = "Common Variables Controller V2")
public class CommonVariablesControllerV2 {

    private final CommonVariablesService commonVariablesService;

    @Autowired
    public CommonVariablesControllerV2(CommonVariablesService commonVariablesService) {
        this.commonVariablesService = commonVariablesService;
    }

    @PostMapping(value = "/import")
    public ResponseEntity<ImportVariablesResult> importVariablesInternal(
            @RequestParam MultipartFile file,
            @RequestParam(required = false) Set<String> variablesNames
    ) {
        log.info("Request to import common variables");
        ImportVariablesResult importVariablesResult = commonVariablesService.importVariables(file, variablesNames);
        return ResponseEntity.ok(importVariablesResult);
    }
}
