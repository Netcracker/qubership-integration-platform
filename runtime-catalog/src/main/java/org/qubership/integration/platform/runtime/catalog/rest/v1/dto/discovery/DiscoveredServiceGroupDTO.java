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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.discovery;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Specification (group) that was once discovered on some environment")
public class DiscoveredServiceGroupDTO {
    @Schema(description = "Specification group id")
    private String id;
    @Schema(description = "Specification group name")
    private String name;
    @Schema(description = "Whether update on next discovery enabled for that particular specification")
    private boolean synchronization;
    @Schema(description = "Specification id")
    private String specificationId;
    @Schema(description = "Specification name")
    private String specificationName;
    @Schema(description = "Timestamp of creation date")
    private Long createdWhen;

}
