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

package org.qubership.integration.platform.variables.management.rest.v2.dto.variables;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

import static org.qubership.integration.platform.variables.management.validation.EntityValidator.ENTITY_NAME_REGEXP;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Request object for secured variables api")
public class SecuredVariablesRequest {

    @Schema(description = "Secret name")
    private String secretName;
    @Schema(description = "Set of secured variables <key, value>")
    private Map<@Pattern(regexp = ENTITY_NAME_REGEXP, message = "does not match \"{regexp}\"") String, String> variables =
            new HashMap<>();
}
