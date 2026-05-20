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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.chain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ImportChainResult;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Deprecated(since = "2023.4")
@Schema(description = "Import result")
public class ImportDTO {
    @Schema(description = "Global error message (if any)")
    String errorMessage;

    @Builder.Default
    @Schema(description = "List of results by each chain")
    List<ImportChainResult> chains = new ArrayList<>();
}
