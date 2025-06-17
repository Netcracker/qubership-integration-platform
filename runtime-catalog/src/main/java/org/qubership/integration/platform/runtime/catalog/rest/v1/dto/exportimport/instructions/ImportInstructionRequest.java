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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.instructions;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.validation.constraint.ChainNotOverrideItself;
import org.qubership.integration.platform.runtime.catalog.validation.constraint.NotStartOrEndWithSpace;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@ToString
@Schema(description = "Import instruction create/update object")
@ChainNotOverrideItself
public class ImportInstructionRequest {

    @Schema(description = "Import instruction id")
    @NotStartOrEndWithSpace(message = "must not be empty and must not start or end with a space")
    private String id;

    @Schema(description = "The type of entity to which instruction will apply", allowableValues = { "CHAIN", "SERVICE" })
    @NotNull(message = "must not be null")
    @Pattern(regexp = "CHAIN|SERVICE", message = "must be CHAIN or SERVICE")
    private String entityType;

    @Schema(description = "Import instruction action", allowableValues = { "IGNORE", "OVERRIDE" })
    @NotNull(message = "must not be null")
    @Pattern(regexp = "IGNORE|OVERRIDE", message = "must be IGNORE or OVERRIDE")
    private String action;

    @Schema(description = "Id of chain to be overridden")
    @NotStartOrEndWithSpace(optional = true, message = "must not start or end with a space")
    private String overriddenBy;
}
