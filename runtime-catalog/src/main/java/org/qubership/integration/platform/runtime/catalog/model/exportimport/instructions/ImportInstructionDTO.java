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

package org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Import instruction response object")
public class ImportInstructionDTO {

    @Schema(description = "Import instruction/entity id")
    private String id;
    @Schema(description = "Entity name")
    private String name;
    @Schema(description = "Id of chain to be overridden")
    private String overriddenById;
    @Schema(description = "Name of chain to be overridden")
    private String overriddenByName;
    @Schema(description = "List of import instruction labels")
    @Builder.Default
    private Set<String> labels = new HashSet<>();
    @Schema(description = "Timestamp of object last modification")
    private Long modifiedWhen;
    @Schema(description = "Indicates whether the import instruction stored in DB")
    private boolean preview;
}
