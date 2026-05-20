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
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DetailedDesignTemplate;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.dds.DDSResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.dds.DDSTemplateCreateRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.dds.DDSTemplateResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DetailedDesignMapper;
import org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.DetailedDesignService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping(value = "/v1/detailed-design", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "detailed-design-controller", description = "Detailed Design Controller")
@CrossOrigin(origins = "*")
public class DetailedDesignController {

    private final DetailedDesignService detailedDesignService;
    private final DetailedDesignMapper detailedDesignMapper;

    @Autowired
    public DetailedDesignController(DetailedDesignService detailedDesignService, DetailedDesignMapper detailedDesignMapper) {
        this.detailedDesignService = detailedDesignService;
        this.detailedDesignMapper = detailedDesignMapper;
    }

    @GetMapping("/chains/{chainId}")
    @Operation(description = "Get chain detailed design by chainId and templateId")
    public ResponseEntity<DDSResponse> getChainDesign(@PathVariable("chainId") String chainId, @RequestParam("templateId") String templateId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to generate detailed design for chain: {}, by template: {}", chainId, templateId);
        }

        return ResponseEntity.ok(detailedDesignService.buildChainDetailedDesign(chainId, templateId));
    }

    @GetMapping("/templates/{templateId}")
    @Operation(description = "Get detailed design template by id")
    public ResponseEntity<DDSTemplateResponse> getTemplate(@PathVariable("templateId") String templateId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get detailed design template: {}", templateId);
        }
        return ResponseEntity.ok(detailedDesignMapper.asResponse(detailedDesignService.getTemplate(templateId)));
    }

    @GetMapping("/templates")
    @Operation(description = "Get all detailed design templates")
    public ResponseEntity<List<DDSTemplateResponse>> getTemplates(@RequestParam(defaultValue = "true") boolean includeContent) {
        if (log.isDebugEnabled()) {
            log.debug("Request to get detailed design templates");
        }
        List<DDSTemplateResponse> customResponse = detailedDesignMapper.asResponses(detailedDesignService.findCustomTemplates());
        List<DDSTemplateResponse> builtInResponse = detailedDesignMapper.asResponses(detailedDesignService.getBuiltInTemplates());

        List<DDSTemplateResponse> result = Stream.concat(
                customResponse.stream().peek(response -> response.setBuiltIn(false)),
                builtInResponse.stream().peek(response -> response.setBuiltIn(true))).toList();
        if (!includeContent) {
            result.forEach(template -> template.setContent(null));
        }

        return ResponseEntity.ok(result);
    }

    @PutMapping("/templates")
    @Operation(description = "Create detailed design template")
    public ResponseEntity<DDSTemplateResponse> createTemplate(@RequestBody DDSTemplateCreateRequest request) {
        log.info("Request to create or update detailed design template");
        DetailedDesignTemplate template = detailedDesignService.createOrUpdateTemplate(request.getName(), request.getContent());
        return ResponseEntity.ok(detailedDesignMapper.asResponse(template));
    }

    @DeleteMapping("/templates")
    @Operation(description = "Delete detailed design template")
    public ResponseEntity<Void> deleteTemplates(@RequestParam("ids") List<String> ids) {
        log.info("Request to delete detailed design templates with ids: {}", ids);
        detailedDesignService.deleteTemplates(ids);
        return ResponseEntity.noContent().build();
    }
}
