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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;

import java.util.List;

@Getter
@Setter
@SuperBuilder
@Schema(description = "Response object for a single chain without chain elements")
public class ChainResponse extends BaseResponse {
    @Schema(description = "Parent id of the chain (folder id)")
    private String parentId;
    @Schema(description = "Id of default swimlane on chain graph (if any)")
    private String defaultSwimlaneId;
    @Schema(description = "Id of reuse swimlane on chain graph (if any)")
    private String reuseSwimlaneId;
    @Schema(description = "Labels assigned to the chain")
    private List<ChainLabelDTO> labels;

}
