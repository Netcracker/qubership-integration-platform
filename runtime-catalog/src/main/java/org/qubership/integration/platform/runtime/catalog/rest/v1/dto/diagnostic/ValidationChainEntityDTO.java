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

import java.util.Map;

/**
 * Represents validation entity (chain, chain element)
 */
@Getter
@Setter
@Builder
@Schema(description = "Entity related to validation in which problems were found as a result of diagnostics")
public class ValidationChainEntityDTO {
    @Schema(description = "Chain id")
    private String chainId;
    @Schema(description = "Chain name")
    private String chainName;

    @Schema(description = "Element id")
    private String elementId;
    @Schema(description = "Element name")
    private String elementName;
    @Schema(description = "Element type")
    private String elementType;

    @Schema(description = "Entity and validation related parameters")
    private Map<String, Object> properties;
}
