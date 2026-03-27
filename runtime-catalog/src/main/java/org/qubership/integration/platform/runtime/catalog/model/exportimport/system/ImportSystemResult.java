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

package org.qubership.integration.platform.runtime.catalog.model.exportimport.system;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.ImportSystemStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.remote.SystemCompareAction;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "Result object of service import")
public class ImportSystemResult {
    @Schema(description = "Id")
    private String id;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Archive name that service were imported from")
    private String archiveName;

    @Schema(description = "Timestamp of last modification date")
    private Long modified;

    @Schema(description = "Service import status")
    private ImportSystemStatus status;

    @Schema(description = "Service import action")
    private SystemCompareAction requiredAction;

    @Schema(description = "Type of the service")
    @Deprecated //Deprecated in 24.3
    private String systemType;

    @Schema(description = "Service import instruction action")
    private ImportInstructionAction instructionAction;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Warning or error message (if any)")
    private String message;
}
