package org.qubership.integration.platform.engine.service.debugger.sessions;

import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.model.DomainType;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;

import java.util.Map;

public record SessionStepLogContext(
        Exchange exchange,
        String sessionId,
        String sessionElementId,
        String nodeId,
        String stepName,
        String stepChainElementId,
        String bodyForLogging,
        Map<String, String> headersForLogging,
        Map<String, String> contextHeaders,
        Map<String, SessionElementProperty> propertiesForLogging,
        CamelDebuggerProperties dbgProperties,
        String domain,
        DomainType domainType) {
}
