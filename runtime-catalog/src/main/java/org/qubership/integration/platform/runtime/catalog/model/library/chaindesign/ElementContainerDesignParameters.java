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

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Additional element parameters for container elements (with children) for chain sequence diagram building")
public class ElementContainerDesignParameters {

    /**
     * Operation type for first children
     */
    @Schema(description = "Operation type for first children")
    private DiagramOperationType firstChildrenType;

    /**
     * Operation type for others children
     */
    @Schema(description = "Operation type for others children")
    private DiagramOperationType childrenType;

    /**
     * Operations (in proper order) applied after processing of child elements is completed
     */
    @Schema(description = "Operations (in proper order) applied after processing of child elements is completed")
    private List<ElementDiagramOperation> endOperations = new ArrayList<>();

    /**
     * Container children in proper order (starts from the main element)
     */
    @Schema(description = "Container children in proper order (starts from the main element)")
    private List<ContainerChildrenParameters> children = new ArrayList<>();

}
