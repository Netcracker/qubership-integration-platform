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

package org.qubership.integration.platform.runtime.catalog.model.exportimport;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionStatus;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.system.ImportSystemResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.variable.ImportVariableResult;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain.ImportEntityStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "Import preview result")
public class ImportResult {

    @Builder.Default
    @Schema(description = "List of results by each chain")
    private List<ImportChainResult> chains = new ArrayList<>();
    @Builder.Default
    @Schema(description = "List of results by each service")
    private List<ImportSystemResult> systems = new ArrayList<>();
    @Builder.Default
    @Schema(description = "List of results by each service")
    private List<ImportSystemResult> contextService = new ArrayList<>();
    @Builder.Default
    @Schema(description = "List of results by each variable")
    private List<ImportVariableResult> variables = new ArrayList<>();
    @Builder.Default
    @Schema(description = "List of results by each instruction")
    private List<ImportInstructionResult> instructionsResult = new ArrayList<>();

    public boolean hasErrors() {
        return hasChainErrors() || hasSystemErrors() || hasContextServiceErrors()
                || hasVariableErrors() || hasInstructionErrors();
    }

    public boolean hasChainErrors() {
        return chains.stream().anyMatch(chain -> ImportEntityStatus.ERROR.equals(chain.getStatus()));
    }

    public boolean hasSystemErrors() {
        return systems.stream().anyMatch(system -> ImportSystemStatus.ERROR.equals(system.getStatus()));
    }

    public boolean hasContextServiceErrors() {
        return contextService.stream().anyMatch(system -> ImportSystemStatus.ERROR.equals(system.getStatus()));
    }

    private boolean hasVariableErrors() {
        return variables.stream().anyMatch(variable -> ImportEntityStatus.ERROR.equals(variable.getStatus()));
    }

    private boolean hasInstructionErrors() {
        return instructionsResult.stream().anyMatch(instruction ->
                ImportInstructionStatus.ERROR_ON_DELETE.equals(instruction.getStatus())
                        || ImportInstructionStatus.ERROR_ON_OVERRIDE.equals(instruction.getStatus()));
    }
}
