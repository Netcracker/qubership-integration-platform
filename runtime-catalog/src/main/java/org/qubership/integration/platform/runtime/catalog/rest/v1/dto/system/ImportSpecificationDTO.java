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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Schema(description = "Import specification result")
public class ImportSpecificationDTO {
    @Schema(description = "Id")
    private String id;
    @Schema(description = "Description")
    private String description;
    @Schema(description = "Warning message (if any)")
    private String warningMessage;
    @Schema(description = "Whether import is done")
    private boolean isDone;
    @Schema(description = "Specification group id")
    private String specificationGroupId;

    public ImportSpecificationDTO(String id, Boolean isDone) {
        this.id = id;
        this.isDone = isDone;
    }
}
