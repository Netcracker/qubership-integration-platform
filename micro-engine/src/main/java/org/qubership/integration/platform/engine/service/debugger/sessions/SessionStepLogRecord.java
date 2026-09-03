package org.qubership.integration.platform.engine.service.debugger.sessions;

import org.qubership.integration.platform.engine.model.engine.DomainType;
import org.qubership.integration.platform.engine.service.ExecutionStatus;

public record SessionStepLogRecord(
        String domain,
        DomainType domainType,
        String snapshotName,
        String parentElementId,
        String camelElementName,
        ExecutionStatus executionStatus,
        String bodyAfter,
        String headersAfter,
        String propertiesAfter,
        String elementName,
        String elementId,
        String chainElementId) {
}
