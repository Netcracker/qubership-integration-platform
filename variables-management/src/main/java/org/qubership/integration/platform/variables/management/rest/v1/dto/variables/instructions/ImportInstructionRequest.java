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

package org.qubership.integration.platform.variables.management.rest.v1.dto.variables.instructions;

import org.qubership.integration.platform.variables.management.validation.constraint.NotStartOrEndWithSpace;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import static org.qubership.integration.platform.variables.management.validation.EntityValidator.ENTITY_NAME_REGEXP;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@ToString
@Schema(description = "Import instruction create/update object")
public class ImportInstructionRequest {

    @Schema(description = "Import instruction id")
    @NotStartOrEndWithSpace(message = "must not be null and must not start or end with a space")
    @Pattern(regexp = ENTITY_NAME_REGEXP, message = "does not match \"{regexp}\"")
    private String id;
    @Schema(description = "Import instruction action", allowableValues = "IGNORE")
    @NotNull(message = "must not be null")
    @Pattern(regexp = "IGNORE", message = "must be IGNORE")
    private String action;
}
