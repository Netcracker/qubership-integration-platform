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

package org.qubership.integration.platform.variables.management.model.exportimport.instructions;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.variables.management.validation.constraint.NotStartOrEndWithSpace;

import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@Schema(description = "Entity import instruction config response object")
public class ImportInstructionsConfig {

    @Schema(description = "List of delete instruction ids")
    @Builder.Default
    private Set<@NotStartOrEndWithSpace(message = "must not be null and must not start or end with a space") String> delete = new HashSet<>();
    @Schema(description = "List of ignore instruction ids")
    @Builder.Default
    private Set<@NotStartOrEndWithSpace(message = "must not be null and must not start or end with a space") String> ignore = new HashSet<>();
}
