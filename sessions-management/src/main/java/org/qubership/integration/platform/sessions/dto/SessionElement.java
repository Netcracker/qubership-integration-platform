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

package org.qubership.integration.platform.sessions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.sessions.dto.opensearch.ExceptionInfoElastic;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Single element(step) from the session")
public class SessionElement extends AbstractRunnableElement {

    @Schema(description = "Id of the object")
    private String elementId;

    @Schema(description = "Id of the session linked to session element")
    private String sessionId;

    @Schema(description = "Id of the chain element linked to session element")
    private String chainElementId;

    @Schema(description = "Id of the sub-chain element linked to session element in case (in case it is element from sub-chain)")
    private String actualElementChainId;

    @Schema(description = "Id of the parent object for current object")
    private String parentElement;

    @Schema(description = "Previous object id in execution chain")
    private String previousElement;

    @Schema(description = "Name of the object (step)")
    private String elementName;

    @Schema(description = "Type of the element if it is chain element step (not sub-step)")
    private String camelName;

    @Schema(description = "Body before step execution")
    private String bodyBefore;

    @Schema(description = "Body after step execution")
    private String bodyAfter;

    @Schema(description = "Map of headers before step execution")
    private Map<String, String> headersBefore;

    @Schema(description = "Map of headers after step execution")
    private Map<String, String> headersAfter;

    @Schema(description = "Map of properties before step execution")
    private Map<String, SessionElementProperty> propertiesBefore;

    @Schema(description = "Map of properties after step execution")
    private Map<String, SessionElementProperty> propertiesAfter;

    @Schema(description = "Map of context properties before step execution")
    private Map<String, String> contextBefore;

    @Schema(description = "Map of context properties after step execution")
    private Map<String, String> contextAfter;

    @Schema(description = "List of child elements (sub-elements) for the current object (step)")
    private List<SessionElement> children;

    private ExceptionInfoElastic exceptionInfo;
}
