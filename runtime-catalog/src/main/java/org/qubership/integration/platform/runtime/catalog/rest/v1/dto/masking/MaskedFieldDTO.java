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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.masking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Masked field")
public class MaskedFieldDTO {
    @Schema(description = "Id")
    private String id;
    @Schema(description = "Masked field name")
    private String name;
    @Schema(description = "Timestamp of creation date")
    private Long createdWhen;
    @Schema(description = "User who created entity")
    private User createdBy;
    @Schema(description = "Timestamp of last modification date")
    private Long modifiedWhen;
    @Schema(description = "User who last modified entity")
    private User modifiedBy;
}
