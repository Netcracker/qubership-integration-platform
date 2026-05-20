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

package org.qubership.integration.platform.runtime.catalog.model.library.chaindesign;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType;

@Data
@Schema(description = "Operations that can be applied on diagram")
public class ElementDiagramOperation {

    @Schema(description = "Diagram operation type")
    private DiagramOperationType type;

    /**
     * Operation arguments in proper order
     */
    @Schema(description = "Operation arguments in proper order")
    private String[] args = new String[0];
}
