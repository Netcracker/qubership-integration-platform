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

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Schema(description = "Chain creation request object")
public class ChainRequest {
    @Schema(description = "Chain name")
    private String name;
    @Schema(description = "Chain description")
    private String description;
    @Schema(description = "'Business description' for chain documentation")
    private String businessDescription;
    @Schema(description = "'Assumptions' for chain documentation")
    private String assumptions;
    @Schema(description = "'Out of scope' for chain documentation")
    private String outOfScope;
    @Schema(description = "Parent (folder) id")
    private String parentId;
    @Schema(description = "List of labels")
    private List<ChainLabelDTO> labels;

}
