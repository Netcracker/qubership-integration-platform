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

package org.qubership.integration.platform.sessions.dto.opensearch;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.sessions.dto.ExecutionStatus;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class SessionElementElastic extends AbstractElement {

    private String sessionId;

    private String externalSessionId;

    private String sessionStarted;

    private String sessionFinished;

    private long sessionDuration;

    private ExecutionStatus sessionExecutionStatus;

    private long sessionSyncDuration;

    private boolean importedSession;

    private String chainId;

    private String chainName;

    private String domain;

    private String engineAddress;

    private String loggingLevel;

    private String snapshotName;

    private String correlationId;

    private String chainElementId;

    private String actualElementChainId;

    private String elementName;

    private String camelElementName;

    private String prevElementId;

    private String parentElementId;

    private String bodyBefore;

    private String bodyAfter;

    private String headersBefore;

    private String headersAfter;

    private String propertiesBefore;

    private String propertiesAfter;

    private String contextBefore;

    private String contextAfter;

    private String parentSessionId;

    private ExceptionInfoElastic exceptionInfo;
}
