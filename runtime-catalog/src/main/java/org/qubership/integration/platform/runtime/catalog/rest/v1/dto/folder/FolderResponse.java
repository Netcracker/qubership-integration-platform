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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainLabelDTO;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Folder response object")
public class FolderResponse extends BaseResponse {
    @Schema(description = "Path to current folder")
    private Map<String, String> navigationPath;

    @Schema(description = "Parent folder id (if any)")
    private String parentId;

    @Schema(description = "List of items contained in the folder")
    private List<FolderItemResponse> items;

    @Schema(description = "Assigned labels")
    private List<ChainLabelDTO> labels;

}
