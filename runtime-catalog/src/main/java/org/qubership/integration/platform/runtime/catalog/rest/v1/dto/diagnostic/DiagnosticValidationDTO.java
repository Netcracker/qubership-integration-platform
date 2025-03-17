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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.diagnostic;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationImplementationType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationEntityType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationSeverity;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

@Getter
@Setter
@Builder
@Schema(description = "Response object for diagnostic validation entity")
public class DiagnosticValidationDTO {
    @Schema(description = "Validation id")
    private String id;
    @Schema(description = "Validation name")
    private String title;
    @Schema(description = "Validation description")
    private String description;
    @Schema(description = "Validation suggestion (hint)")
    private String suggestion;
    @Schema(description = "Type of entity with which validation works")
    private ValidationEntityType entityType;
    @Schema(description = "Validation implementation source")
    private ValidationImplementationType implementationType;
    @Schema(description = "Validation severity")
    private ValidationSeverity severity;
    @Schema(description = "Validation execution properties in key:value format")
    private Map<String, Object> properties;
    @Schema(description = "Validation last execution status")
    private ValidationStatusDTO status;
    @Schema(description = "Validation alerts count")
    private long alertsCount;
    @Nullable
    @Schema(description = "Entities related to this validation in which problems were found as a result of diagnostics")
    private List<ValidationChainEntityDTO> chainEntities;
}
