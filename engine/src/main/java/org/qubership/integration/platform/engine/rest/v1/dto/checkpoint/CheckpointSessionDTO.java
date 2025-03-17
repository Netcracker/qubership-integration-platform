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

package org.qubership.integration.platform.engine.rest.v1.dto.checkpoint;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.qubership.integration.platform.engine.service.ExecutionStatus;

import java.util.List;

@Data
@Builder
@Schema(description = "Single chain session with available checkpoints list")
public class CheckpointSessionDTO {
    @Schema(description = "Id of the session")
    private String id;

    @Schema(description = "Date time of session start")
    private String started;

    @Schema(description = "Date time of session end")
    private String finished;

    @Schema(description = "Duration of session execution, in ms")
    private Long duration;

    @Schema(description = "Duration of session execution, in ms")
    private ExecutionStatus executionStatus;

    @Schema(description = "Id of the chain it was executed on")
    private String chainId;

    @Schema(description = "Name of the chain it was executed on")
    private String chainName;

    @Schema(description = "engine pod ip address on which chain was executed")
    private String engineAddress;

    @Schema(description = "Value of logging level on a chain at a time chain was executed")
    private String loggingLevel;

    @Schema(description = "Deployed snapshot name for the chain")
    private String snapshotName;

    @Schema(description = "Correlation id for that execution (if it was set)")
    private String correlationId;

    @Schema(description = "List of available checkpoints for that session")
    List<CheckpointDTO> checkpoints;
}
