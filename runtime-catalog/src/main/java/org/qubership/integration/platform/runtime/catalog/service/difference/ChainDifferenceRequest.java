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

package org.qubership.integration.platform.runtime.catalog.service.difference;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request object containing identification information to find differences between chains/snapshots")
public class ChainDifferenceRequest {

    @Schema(description = "Original chain id", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "The leftChainId must not be null")
    private String leftChainId;
    @Schema(description = "Original snapshot id")
    private String leftSnapshotId;
    @Schema(description = "Modified chain id", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "The rightChainId must not be null")
    private String rightChainId;
    @Schema(description = "Modified snapshot id")
    private String rightSnapshotId;
}
