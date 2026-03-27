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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.qubership.integration.platform.runtime.catalog.model.dto.dependency.DependencyResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.ElementResponse;

import java.util.List;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "Difference list for the chain after some action")
public class ChainDiffResponse {
    @Schema(description = "Created elements")
    private List<ElementResponse> createdElements;
    @Schema(description = "Updated elements")
    private List<ElementResponse> updatedElements;
    @Schema(description = "Removed elements")
    private List<ElementResponse> removedElements;
    @Schema(description = "Created default swimlane id (if any)")
    private String createdDefaultSwimlaneId;
    @Schema(description = "Created reuse swimlane id (if any)")
    private String createdReuseSwimlaneId;
    @Schema(description = "Created dependencies (links between elements)")
    private List<DependencyResponse> createdDependencies;
    @Schema(description = "Removed dependencies (links between elements)")
    private List<DependencyResponse> removedDependencies;
}
