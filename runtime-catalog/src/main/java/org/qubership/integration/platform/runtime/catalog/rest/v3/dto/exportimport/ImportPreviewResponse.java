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

package org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.GeneralImportInstructionsDTO;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportChainPreviewDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.variable.ImportVariablePreviewResult;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "Import preview result")
public class ImportPreviewResponse {

    @Builder.Default
    @Schema(description = "List of results by each chain")
    private List<ImportChainPreviewDTO> chains = new ArrayList<>();
    @Builder.Default
    @Schema(description = "List of results by each service")
    private List<ImportSystemResult> systems = new ArrayList<>();
    @Builder.Default
    @Schema(description = "List of results by each variable")
    private List<ImportVariablePreviewResult> variables = new ArrayList<>();
    @Schema(description = "Import instructions preview")
    private GeneralImportInstructionsDTO instructions;
}
