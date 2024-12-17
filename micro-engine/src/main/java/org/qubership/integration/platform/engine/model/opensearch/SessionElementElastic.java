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

package org.qubership.integration.platform.engine.model.opensearch;

import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchDocument;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchField;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@OpenSearchDocument(documentNameProperty = "qip.opensearch.index.elements.name")
public class SessionElementElastic extends AbstractElementElastic {

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String sessionId;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String externalSessionId;

    @OpenSearchField(type = OpenSearchFieldType.Date)
    private String sessionStarted;

    @OpenSearchField(type = OpenSearchFieldType.Date)
    private String sessionFinished;

    private long sessionDuration;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private ExecutionStatus sessionExecutionStatus;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String chainId;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String actualElementChainId;

    private String chainName;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String domain;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String engineAddress;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String loggingLevel;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String snapshotName;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String correlationId;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String chainElementId;

    private String elementName;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String camelElementName;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String prevElementId;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String parentElementId;

    @OpenSearchField(type = OpenSearchFieldType.Keyword)
    private String parentSessionId;

    private String bodyBefore;

    private String bodyAfter;

    private String headersBefore;

    private String headersAfter;

    private String propertiesBefore;

    private String propertiesAfter;

    private String contextBefore;

    private String contextAfter;

    @OpenSearchField(type = OpenSearchFieldType.Object)
    private ExceptionInfo exceptionInfo;

    public void updateRelatedSessionData(Session session) {
        if (session != null) {
            setExternalSessionId(session.getExternalId());
            setSessionStarted(session.getStarted());
            setSessionFinished(session.getFinished());
            setSessionDuration(session.getDuration());
            setSessionExecutionStatus(session.getExecutionStatus());
            setChainId(session.getChainId());
            setChainName(session.getChainName());
            setDomain(session.getDomain());
            setEngineAddress(session.getEngineAddress());
            setLoggingLevel(session.getLoggingLevel());
            setCorrelationId(session.getCorrelationId());
            setSnapshotName(session.getSnapshotName());
            setParentSessionId(session.getParentSessionId());
        }
    }
}
