package org.qubership.integration.platform.engine.service.debugger.sessions;

import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.model.DomainType;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.logging.SessionLogDetails;
import org.qubership.integration.platform.engine.service.debugger.util.DebuggerUtils;
import org.qubership.integration.platform.engine.service.debugger.util.MaskedFieldUtils;
import org.qubership.integration.platform.engine.service.debugger.util.PayloadExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class JsonSessionStepCoordinator {

    private final SessionsService sessionsService;
    private final PayloadExtractor payloadExtractor;
    private final ServerConfiguration serverConfiguration;

    @Autowired
    public JsonSessionStepCoordinator(SessionsService sessionsService,
            PayloadExtractor payloadExtractor,
            ServerConfiguration serverConfiguration) {
        this.sessionsService = sessionsService;
        this.payloadExtractor = payloadExtractor;
        this.serverConfiguration = serverConfiguration;
    }

    public record LoggingPayload(String bodyForLogging,
            Map<String, String> headersForLogging,
            Map<String, SessionElementProperty> exchangePropertiesForLogging) {
    }

    public void handleElementJsonLogging(Exchange exchange,
            CamelDebuggerProperties dbgProperties,
            String nodeId,
            String elementId,
            String sessionId,
            String sessionElementId,
            LoggingPayload payload) {
        SessionLogDetails details = dbgProperties.getRuntimeProperties(exchange).getSessionLogDetails();
        if (details == SessionLogDetails.OFF
                || CamelConstants.CUSTOM_STEP_ID_PATTERN.matcher(nodeId).matches()
                || isStepNode(exchange, elementId)) {
            return;
        }
        sessionsService.recordStepAfter(new SessionStepLogContext(
                exchange,
                sessionId,
                sessionElementId,
                elementId,
                null,
                null,
                payload.bodyForLogging(),
                payload.headersForLogging(),
                payloadExtractor.extractContextForLogging(
                        MaskedFieldUtils.getMaskedFields(
                                exchange.getProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY)),
                        dbgProperties.getRuntimeProperties(exchange).isMaskingEnabled()),
                payload.exchangePropertiesForLogging(),
                dbgProperties,
                serverConfiguration.getDomain(),
                DomainType.CLASSIC));
    }

    @SuppressWarnings("unchecked")
    private boolean isStepNode(Exchange exchange, String nodeId) {
        Set<String> stepIds = exchange.getProperty(
                CamelConstants.Properties.SESSION_STEP_IDS, Set.class);
        if (stepIds != null && stepIds.contains(nodeId)) {
            return true;
        }
        Map<String, String> stepNodeIds = exchange.getProperty(
                CamelConstants.Properties.SESSION_STEP_NODE_IDS, Map.class);
        return stepNodeIds != null && stepNodeIds.containsValue(nodeId);
    }

    public void handleStepFinishedJsonLogging(Exchange exchange,
            CamelDebuggerProperties dbgProperties,
            String fullStepId,
            String stepSessionElementId) {
        SessionLogDetails details = dbgProperties.getRuntimeProperties(exchange).getSessionLogDetails();
        if (details == SessionLogDetails.OFF || stepSessionElementId == null) {
            return;
        }
        String sessionId = exchange.getProperty(CamelConstants.Properties.SESSION_ID, String.class);
        if (sessionId == null) {
            return;
        }
        String stepId = DebuggerUtils.getNodeIdFormatted(fullStepId);
        String stepName = DebuggerUtils.getStepNameFormatted(fullStepId);
        String stepChainElementId = DebuggerUtils.getStepChainElementId(fullStepId);
        Set<String> maskedFields = MaskedFieldUtils.getMaskedFields(
                exchange.getProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY));
        boolean maskingEnabled = dbgProperties.getRuntimeProperties(exchange).isMaskingEnabled();
        String bodyForLogging = payloadExtractor.extractBodyForLogging(exchange, maskedFields, maskingEnabled);
        Map<String, String> headersForLogging = payloadExtractor.extractHeadersForLogging(
                exchange, maskedFields, maskingEnabled);
        Map<String, String> contextHeaders = payloadExtractor.extractContextForLogging(
                maskedFields, maskingEnabled);
        Map<String, SessionElementProperty> propertiesForLogging =
                payloadExtractor.extractExchangePropertiesForLogging(exchange, maskedFields, maskingEnabled);
        if (CamelConstants.CUSTOM_STEP_ID_PATTERN.matcher(fullStepId).matches()) {
            sessionsService.recordStepAfterForStep(new SessionStepLogContext(
                    exchange, sessionId, stepSessionElementId, null, stepName, stepChainElementId,
                    bodyForLogging, headersForLogging, contextHeaders, propertiesForLogging,
                    dbgProperties, serverConfiguration.getDomain(), DomainType.CLASSIC));
        } else {
            sessionsService.recordStepAfter(new SessionStepLogContext(
                    exchange, sessionId, stepSessionElementId, stepId, null, null,
                    bodyForLogging, headersForLogging, contextHeaders, propertiesForLogging,
                    dbgProperties, serverConfiguration.getDomain(), DomainType.CLASSIC));
        }
    }
}
