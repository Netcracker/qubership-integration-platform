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
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Single session object")
public class Session extends AbstractRunnableElement {

    @Schema(description = "Id")
    private String id;

    @Schema(description = "Whether this is imported session")
    private boolean importedSession;

    @Schema(description = "External id (if it was set during chain execution)")
    private String externalSessionCipId;

    @Schema(description = "Id of the chain it was executed on")
    private String chainId;

    @Schema(description = "Name of the chain it was executed on")
    private String chainName;

    @Schema(description = "Domain on which chain was executed")
    private String domain;

    @Schema(description = "qubership-integration-platform-engine pod ip address on which chain was executed")
    private String engineAddress;

    @Schema(description = "Value of logging level on a chain at a time chain was executed")
    private String loggingLevel;

    @Schema(description = "Deployed snapshot name for the chain")
    private String snapshotName;

    @Schema(description = "Correlation id for that execution (if it was set)")
    private String correlationId;

    @Schema(description = "Parent of session id for the current session")
    private String parentSessionId;

    @Schema(description = "List of session elements (steps) created by this session")
    private List<SessionElement> sessionElements;

}
