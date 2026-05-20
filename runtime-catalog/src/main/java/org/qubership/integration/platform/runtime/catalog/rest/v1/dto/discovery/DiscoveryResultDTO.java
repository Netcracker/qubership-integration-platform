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

import java.util.List;

@Data
@Schema(description = "Result of a discovery process")
public class DiscoveryResultDTO {
    @Schema(description = "List of created service ids")
    private List<String> discoveredSystemIds;
    @Schema(description = "List of created specification group ids")
    private List<String> discoveredGroupIds;
    @Schema(description = "List of created specification ids")
    private List<String> discoveredSpecificationIds;
    @Schema(description = "List of updated service ids")
    private List<String> updatedSystemsIds;
    @Schema(description = "List of error messages")
    private List<DiscoveryErrorDTO> errorMessages;
}
